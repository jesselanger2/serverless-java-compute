package edu.yu.cs.com3800.stage5;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import edu.yu.cs.com3800.LoggingServer;
import edu.yu.cs.com3800.Vote;
import edu.yu.cs.com3800.Message;
import edu.yu.cs.com3800.Util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public class GatewayServer extends Thread implements LoggingServer {

    private final ConcurrentHashMap<Integer, Response> cache;
    private final ConcurrentHashMap<Long, InetSocketAddress> peerIDtoAddress;
    private volatile InetSocketAddress leaderAddress;
    private final int httpPort;
    private final Logger logger;
    private final GatewayPeerServerImpl gatewayPeerServer;
    private final HttpServer httpServer;
    private final Queue<QueuedRequest> queuedRequests;
    private final AtomicLong nextRequestID;

    private record Response(int responseCode, String response) {}
    private record QueuedRequest(HttpExchange exchange, byte[] requestBody, int code) {}

    public GatewayServer(int httpPort,
                         int peerPort,
                         long peerEpoch,
                         Long serverID,
                         ConcurrentHashMap<Long, InetSocketAddress> peerIDtoAddress,
                         int numberOfObservers) throws IOException {
        setName("GatewayServer");
        this.httpPort = httpPort;
        cache = new ConcurrentHashMap<>();
        this.peerIDtoAddress = peerIDtoAddress;
        leaderAddress = null;
        queuedRequests = new LinkedList<>();
        nextRequestID = new AtomicLong(0);
        this.logger = Util.initializeLogging("GatewayServer", serverID, peerPort, "BASIC");
        // create gateway server and run it on a separate thread
        gatewayPeerServer = new GatewayPeerServerImpl(peerPort, peerEpoch, serverID, peerIDtoAddress, serverID, numberOfObservers);
        httpServer = createHttpServer(httpPort);
    }

    @Override
    public void run() {
        logger.info("Starting GatewayPeerServerImpl on TCP port " + gatewayPeerServer.getTcpPort() + " and UDP port " + gatewayPeerServer.getUdpPort());
        gatewayPeerServer.start();
        logger.info("Starting HTTP server on port " + httpPort);
        httpServer.start();
        Vote leader = null;
        while (!isInterrupted()) {
            // get leader address
            Vote newLeader = getLeader();
            if (newLeader == null || newLeader.equals(leader)) {
                Thread.onSpinWait();
                continue;
            }
            logger.info("Leader has changed from " + leader + " to " + newLeader.getProposedLeaderID());
            leader = newLeader;
            Long leaderID = leader.getProposedLeaderID();
            this.leaderAddress = peerIDtoAddress.get(leaderID);
            // process queued requests from previous leader
            QueuedRequest qr;
            while (!gatewayPeerServer.isPeerDead(leaderAddress) && (qr = queuedRequests.poll()) != null) {
                processRequest(qr.exchange, qr.requestBody(), qr.code());
            }
        }
    }

    public int getHttpPort() {
        return httpPort;
    }

    private HttpServer createHttpServer(int httpPort) throws IOException {
        // create server
        HttpServer server = HttpServer.create(new InetSocketAddress(httpPort), 0);
        // create context
        server.createContext("/compileandrun", createHttpHandler());
        server.createContext("/leaderStatus", createLeaderStatusHandler());
        // set executor to use a fixed thread pool
        server.setExecutor(Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() / 2));
        return server;
    }

    private HttpHandler createHttpHandler() {
        return exchange -> {
            String requestMethod = exchange.getRequestMethod();
            String requestHeader = exchange.getRequestHeaders().getFirst("Content-Type");
            if (invalidHttpRequest(exchange, requestMethod, requestHeader)) {
                return;
            }
            // Get the request body
            byte[] requestBody = Util.readAllBytesFromNetwork(exchange.getRequestBody());
            int hashCode = Arrays.hashCode(requestBody);
            logger.info("Received request\n" + new String(requestBody) + "\nfrom client: " + exchange.getRemoteAddress());
            // If the request has already been processed, return the cached response
            if (cache.containsKey(hashCode)) {
                cacheHit(exchange, hashCode);
                return;
            }
            // Make sure the leader is not null before proceeding
            logger.info("Request not found in cache, submitting to leader");
            while (leaderAddress == null) {
                logger.warning("Leader address is null, waiting to process request...");
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            // If the request has not been processed, submit it to the leader and return it to the client
            processRequest(exchange, requestBody, hashCode);
        };
    }

    private HttpHandler createLeaderStatusHandler() {
        return exchange -> {
            String response;
            if (getLeader() == null || gatewayPeerServer.isPeerDead(leaderAddress)) {
                logger.warning("Leader address is null, returning negative leader status to client");
                response = "Negative";
            } else {
                long leaderID = getLeader().getProposedLeaderID();
                logger.info("Leader is server " + leaderID + ", returning leader status to client");
                StringBuilder sb = new StringBuilder();
                sb.append("Leader is server ").append(leaderID).append("\n");
                for (Map.Entry<Long, InetSocketAddress> entry : peerIDtoAddress.entrySet()) {
                    sb.append("Peer ID: ").append(entry.getKey()).append(", Role: ");
                    if (entry.getValue().equals(leaderAddress)) {
                        sb.append("Leader");
                    } else {
                        sb.append("Follower");
                    }
                    sb.append("\n");
                }
                response = sb.toString();
            }
            exchange.sendResponseHeaders(200, response.length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        };
    }

    private boolean invalidHttpRequest(HttpExchange exchange, String requestMethod, String requestHeader) throws IOException {
        // Ensure the request is a POST request
        if (!requestMethod.equals("POST")) {
            logger.warning("Method was " + requestMethod + ", must be POST");
            exchange.sendResponseHeaders(405, -1);
            exchange.getResponseBody().write("Method must be POST".getBytes());
            exchange.close();
            return true;
        }
        // Ensure the request has the correct content type
        if (!requestHeader.equals("text/x-java-source")) {
            logger.warning("Content-Type was " + requestHeader + ", must be text/x-java-source");
            exchange.sendResponseHeaders(400, -1);
            exchange.getResponseBody().write("Content-Type must be text/x-java-source".getBytes());
            exchange.close();
            return true;
        }
        return false;
    }

    private void cacheHit(HttpExchange exchange, int hashCode) throws IOException {
        logger.info("Request has already been processed, returning cached response");
        Response r = cache.get(hashCode);
        exchange.getResponseHeaders().set("Cached-Response", "true");
        exchange.sendResponseHeaders(r.responseCode(), r.response().length());
        exchange.getResponseBody().write(r.response().getBytes());
        exchange.close();
    }

    private void processRequest(HttpExchange exchange, byte[] requestBody, int hashCode) {
        // if the leader is dead, queue the request for the next leader
        if (gatewayPeerServer.isPeerDead(leaderAddress)) {
            logger.warning("Leader died before processing client request, queueing it for next leader");
            queuedRequests.add(new QueuedRequest(exchange, requestBody, hashCode));
            return;
        }
        // send request to leader
        try (exchange; Socket socket = new Socket(leaderAddress.getHostName(), leaderAddress.getPort() + 2)) {
            submitRequest(socket, exchange, requestBody, hashCode);
        } catch (Exception e) {
            logger.severe("Error connecting to leader's socket, queueing request for next leader");
            queuedRequests.add(new QueuedRequest(exchange, requestBody, hashCode));
        }
    }

    private void submitRequest(Socket socket, HttpExchange exchange, byte[] requestBody, int hashCode) throws IOException {
        logger.info("Connected to leader: " + leaderAddress);
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        logger.info("Sending request to leader");
        // send request to leader with new request ID
        out.write(ByteBuffer.allocate(8).putLong(nextRequestID.incrementAndGet()).array());
        out.write(requestBody);
        byte[] response = Util.readAllBytesFromNetwork(in);
        Message msg = new Message(response);
        byte[] responseContents = msg.getMessageContents();
        String responseString = new String(responseContents);
        logger.info("Received response\n" + responseString + "\nfrom leader: " + leaderAddress);
        int responseCode = msg.getErrorOccurred() ? 400 : 200;
        Response r = new Response(responseCode, responseString);
        // leader died before returning response to client
        if (gatewayPeerServer.isPeerDead(leaderAddress)) {
            logger.warning("Leader died before sending response to client, queueing request for next leader");
            queuedRequests.add(new QueuedRequest(exchange, requestBody, hashCode));
            return;
        }
        logger.info("Sending response to client");
        exchange.getResponseHeaders().set("Cached-Response", "false");
        exchange.sendResponseHeaders(r.responseCode(), r.response().length());
        exchange.getResponseBody().write(r.response().getBytes());
        cache.put(hashCode, r);
    }

    public void shutdown() {
        gatewayPeerServer.interrupt();
        httpServer.stop(0);
        interrupt();
    }

    public GatewayPeerServerImpl getPeerServer() {
        return gatewayPeerServer;
    }

    protected Vote getLeader() {
        return gatewayPeerServer.getCurrentLeader();
    }

    public static void main(String[] args) {
        if (args.length != 6) {
            System.err.println("Usage: GatewayServer <httpPort> <peerPort> <gatewayID> <serverIDList> <udpPortList> <numObservers>");
            System.exit(1);
        }
        // parse arguments
        int httpPort = Integer.parseInt(args[0]);
        int peerPort = Integer.parseInt(args[1]);
        long gatewayID = Long.parseLong(args[2]);
        String[] serverIDList = args[3].split(",");
        String[] udpPortList = args[4].split(",");
        int numObservers = Integer.parseInt(args[5]);
        // create peerIDtoAddress map
        ConcurrentHashMap<Long, InetSocketAddress> peerIDtoAddress = new ConcurrentHashMap<>();
        for (int i = 0; i < serverIDList.length; i++) {
            long serverID = Long.parseLong(serverIDList[i]);
            if (serverID != gatewayID) {
                String host = "localhost";
                int port = Integer.parseInt(udpPortList[i]);
                peerIDtoAddress.put(serverID, new InetSocketAddress(host, port));
            }
        }
        // create GatewayServer
        try {
            GatewayServer gatewayServer = new GatewayServer(httpPort, peerPort, 0, gatewayID, peerIDtoAddress, numObservers);
            gatewayServer.start();
            gatewayServer.join();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
