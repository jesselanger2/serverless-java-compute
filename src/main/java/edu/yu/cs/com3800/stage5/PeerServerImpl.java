package edu.yu.cs.com3800.stage5;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import edu.yu.cs.com3800.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.*;

public class PeerServerImpl extends Thread implements PeerServer, LoggingServer {

    private final InetSocketAddress address;                        // address of this server
    private final int udpPort;                                      // UDP port of this server
    private final int tcpPort;                                      // TCP port of this server
    private ServerState state;                                      // state of this server
    private volatile boolean shutdown;                              // flag to indicate if server should shut down
    private final LinkedBlockingQueue<Message> outgoingMessages;    // queue of UDP messages to be sent (for LeaderElection)
    private final LinkedBlockingQueue<Message> incomingMessages;    // queue of UDP messages received (for LeaderElection)
    private final Long id;                                          // ID of this server
    private long epoch;                                             // epoch of this server
    private volatile Vote currentLeader;                            // current leader of the system
    private final Map<Long,InetSocketAddress> peerIDtoAddress;      // map of peer IDs to their addresses
    private final UDPMessageSender sender;                          // thread that sends UDP broadcast messages
    private final UDPMessageReceiver receiver;                      // thread that listens for UDP messages sent to this server
    private final Long gatewayID;                                   // ID of the gateway server
    private final int numberOfObservers;                            // number of observer servers
    private JavaRunnerFollower follower;                            // thread that follows the leader
    private RoundRobinLeader leader;                                // thread that leads the system
    private TCPServer tcpServer;                                    // thread that listens for TCP messages
    private final Gossiper gossiper;                                // thread that handles gossiping and node failure detection
    private final ConcurrentLinkedQueue<Long> failedPeers;          // nodes that have failed and been removed from cluster

    // queue of client requests to be passed between TCPServer and RoundRobinLeader
    private final LinkedBlockingQueue<Message> clientRequests = new LinkedBlockingQueue<>();
    // queue of worker responses to be passed between TCPServer and RoundRobinLeader
    private final LinkedBlockingQueue<Message> workerResponses = new LinkedBlockingQueue<>();

    private final Logger basicLogger;    // basic logger for this server
    private final Logger summaryLogger;  // summary logger for this server
    private HttpServer httpServer;
    private final String summaryLogFilePath;
    private final String verboseLogFilePath;

    public PeerServerImpl(int udpPort,
                          long peerEpoch,
                          Long serverID,
                          Map<Long, InetSocketAddress> peerIDtoAddress,
                          Long gatewayID,
                          int numberOfObservers) throws IOException {
        setName("PeerServerImpl " + serverID);
        // Initialize fields
        this.udpPort = udpPort;
        this.tcpPort = udpPort + 2;
        this.epoch = peerEpoch;
        this.id = serverID;
        this.peerIDtoAddress = peerIDtoAddress;
        address = new InetSocketAddress("localhost", udpPort);
        shutdown = false;
        state = ServerState.LOOKING;
        currentLeader = null;
        this.gatewayID = gatewayID;
        this.numberOfObservers = numberOfObservers;
        outgoingMessages = new LinkedBlockingQueue<>();
        incomingMessages = new LinkedBlockingQueue<>();
        sender = new UDPMessageSender(outgoingMessages, udpPort);
        receiver = new UDPMessageReceiver(incomingMessages, address, udpPort, this);
        failedPeers = new ConcurrentLinkedQueue<>();
        // Initialize loggers
        basicLogger = Util.initializeLogging("PeerServerImpl-BASIC", serverID, udpPort, "BASIC");
        summaryLogger = Util.initializeLogging("PeerServerImpl-SUMMARY", serverID, udpPort, "SUMMARY");
        Logger verboseLogger = Util.initializeLogging("PeerServerImpl-VERBOSE", serverID, udpPort, "VERBOSE");
        this.summaryLogFilePath = String.format("logs/summary_logs/PeerServerImpl-SUMMARY-ID-%d-on-Port-%d-Log.txt", serverID, udpPort);
        this.verboseLogFilePath = String.format("logs/verbose_logs/PeerServerImpl-VERBOSE-ID-%d-on-Port-%d-Log.txt", serverID, udpPort);
        // Initialize HttpServer for serving log files
        this.httpServer = HttpServer.create(new InetSocketAddress(tcpPort + 1), 0);
        this.httpServer.createContext("/summaryLog", new SummaryLogHandler());
        this.httpServer.createContext("/verboseLog", new VerboseLogHandler());
        this.httpServer.setExecutor(null); // creates a default executor
        // Initialize gossiper
        gossiper = new Gossiper(this, peerIDtoAddress, incomingMessages, summaryLogger, verboseLogger);
    }

    private class SummaryLogHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] response = Files.readAllBytes(Paths.get(summaryLogFilePath));
            exchange.sendResponseHeaders(200, response.length);
            OutputStream os = exchange.getResponseBody();
            os.write(response);
            os.close();
        }
    }

    private class VerboseLogHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] response = Files.readAllBytes(Paths.get(verboseLogFilePath));
            exchange.sendResponseHeaders(200, response.length);
            OutputStream os = exchange.getResponseBody();
            os.write(response);
            os.close();
        }
    }

    @Override
    public void run() {
        basicLogger.log(Level.INFO, "Starting PeerServerImpl with UDP port " + udpPort + " and TCP port " + tcpPort);
        // start sender and receiver threads
        basicLogger.log(Level.INFO, "Starting sender thread");
        sender.start();
        basicLogger.log(Level.INFO, "Starting receiver thread");
        receiver.start();
        // start gossiper thread
        gossiper.start();
        basicLogger.log(Level.INFO, "Starting gossiper thread");
        // start HTTP server
        httpServer.start();
        basicLogger.log(Level.INFO, "Starting HTTP server on port " + (tcpPort + 1));
        // main loop for server
        while (!shutdown) {
            switch (getPeerState()) {
                case LOOKING -> looking();
                case LEADING -> leading();
                case FOLLOWING -> following();
                default -> {}
            }
        }
    }

    // Logic for LOOKING state
    private void looking() {
        if (follower != null) {
            summaryLogger.warning(id + ": switching from FOLLOWING to LOOKING");
            System.out.println(id + ": switching from FOLLOWING to LOOKING");
        }
        gossiper.setShouldGossip(false);
        basicLogger.log(Level.INFO, "In LOOKING state, starting leader election");
        // start leader election, set leader to election winner
        LeaderElection election = new LeaderElection(this, gatewayID, incomingMessages, basicLogger);
        Vote leader = election.lookForLeader();
        try {
            basicLogger.log(Level.INFO, "Server " + leader.getProposedLeaderID() + " has been elected as leader");
            setCurrentLeader(leader);
            gossiper.setShouldGossip(true);
        } catch (IOException e) {
            basicLogger.log(Level.SEVERE, "Failed to set current leader", e);
            e.printStackTrace();
        }
    }

    // Logic for LEADING state
    private void leading() {
        if (leader != null) {
            return; // if already leading, do nothing
        }
        // if I was previously following, interrupt that thread
        if (follower != null) {
            follower = null;
            summaryLogger.warning(id + ": switching from FOLLOWING to LEADING");
            System.out.println(id + ": switching from FOLLOWING to LEADING");
        } else {
            summaryLogger.warning(id + ": switching from LOOKING to LEADING");
            System.out.println(id + ": switching from LOOKING to LEADING");
        }
        basicLogger.log(Level.INFO, "In LEADING state, starting TCPServer and RoundRobinLeader");
        // start leading
        tcpServer = new TCPServer(tcpPort, this, clientRequests, workerResponses);
        tcpServer.start();
        leader = new RoundRobinLeader(this, clientRequests, workerResponses, peerIDtoAddress, gatewayID);
        leader.start();
    }

    // Logic for FOLLOWING state
    private void following() {
        if (follower != null) {
            return; // if already following, do nothing
        }
        summaryLogger.warning(id + ": switching from LOOKING to FOLLOWING");
        System.out.println(id + ": switching from LOOKING to FOLLOWING");
        basicLogger.log(Level.INFO, "In FOLLOWING state, starting JavaRunnerFollower");
        // start following
        follower = new JavaRunnerFollower(this);
        follower.start();
    }

    @Override
    public void sendMessage(Message.MessageType type, byte[] messageContents, InetSocketAddress target) throws IllegalArgumentException {
        // Validate arguments
        if (target == null || messageContents == null || type == null) {
            throw new IllegalArgumentException("Arguments cannot be null");
        }
        if (!peerIDtoAddress.containsValue(target)) {
            throw new IllegalArgumentException("Target address is not a valid peer");
        }
        basicLogger.log(Level.INFO, "Sending message of type " + type + " to server on port " + target.getPort());
        // Build message to send to peer
        Message msg = new Message(
                type,
                messageContents,
                address.getHostName(),
                udpPort,
                target.getHostName(),
                target.getPort()
        );
        outgoingMessages.add(msg);
    }

    @Override
    public void sendBroadcast(Message.MessageType type, byte[] messageContents) {
        // Validate arguments
        if (messageContents == null || type == null) {
            throw new IllegalArgumentException("Arguments cannot be null");
        }
        basicLogger.log(Level.INFO, "Sending broadcast message of type " + type);
        for (Map.Entry<Long, InetSocketAddress> entry : peerIDtoAddress.entrySet()) {
            // Get address of peer
            InetSocketAddress target = entry.getValue();
            sendMessage(type, messageContents, target);
        }
    }

    @Override
    public void shutdown() {
        basicLogger.log(Level.WARNING, "Shutting down server " + id + " on UDP port " + udpPort + " and TCP port " + tcpPort);
        // shut down sender, receiver, and gossiper threads
        sender.shutdown();
        receiver.shutdown();
        gossiper.shutdown();
        // interrupt follower and leader threads
        if (follower != null) {
            follower.shutdown();
        }
        if (leader != null) {
            leader.shutdown();
            tcpServer.shutdown();
        }
        // Stop the HttpServer
        httpServer.stop(0);
        // set shutdown flag to true to stop main loop
        shutdown = true;
    }

    @Override
    public void setCurrentLeader(Vote v) throws IOException {
        currentLeader = v;
    }

    @Override
    public Vote getCurrentLeader() {
        return currentLeader;
    }
    
    @Override
    public void reportFailedPeer(Long peerID) {
        basicLogger.severe("Received report of failed peer with ID " + peerID);
        summaryLogger.severe(this.id + ": no heartbeat from server " + peerID + " - SERVER FAILED");
        System.out.println(this.id + ": no heartbeat from server " + peerID + " - SERVER FAILED");
        InetSocketAddress removedAddress;
        synchronized (peerIDtoAddress) {
            // Add to failed peers queue
            failedPeers.add(peerID);
            // Remove from peerIDtoAddress map
            removedAddress = peerIDtoAddress.remove(peerID);
        }
        if (removedAddress != null) {
            basicLogger.log(Level.INFO, "Removed failed peer with ID " + peerID + " and address " + removedAddress);
        } else {
            basicLogger.log(Level.WARNING, "Failed peer ID " + peerID + " not found in peerIDtoAddress map");
        }
        // leader has failed, start leader election
        if (currentLeader.getProposedLeaderID() == peerID) {
            currentLeader = null;
            epoch++;
            setPeerState(ServerState.LOOKING);
        }
    }

    @Override
    public boolean isPeerDead(long peerID) {
        return failedPeers.contains(peerID);
    }

    @Override
    public boolean isPeerDead(InetSocketAddress address) {
        return !peerIDtoAddress.containsValue(address);
    }

    @Override
    public ServerState getPeerState() {
        return state;
    }

    @Override
    public void setPeerState(ServerState newState) {
        state = newState;
    }

    @Override
    public Long getServerId() {
        return id;
    }

    @Override
    public long getPeerEpoch() {
        return epoch;
    }

    @Override
    public InetSocketAddress getAddress() {
        return address;
    }

    @Override
    public int getUdpPort() {
        return udpPort;
    }

    public int getTcpPort() {
        return tcpPort;
    }

    @Override
    public InetSocketAddress getPeerByID(long peerId) {
        return peerIDtoAddress.get(peerId);
    }

    @Override
    public int getQuorumSize() {
        return (peerIDtoAddress.size() - numberOfObservers) / 2 + 1;  // quorum size is the majority of peers (not including observers)
    }

    public static void main(String[] args) {
        if (args.length != 6) {
            System.err.println("Usage: java PeerServerImpl <udpPort> <serverID> <serverIDList> <udpPortList> <gatewayID> <numObservers>");
            System.exit(1);
        }
        // Parse command line arguments
        int udpPort = Integer.parseInt(args[0]);
        long serverID = Long.parseLong(args[1]);
        String[] serverIDList = args[2].split(",");
        String[] udpPortList = args[3].split(",");
        Long gatewayID = Long.parseLong(args[4]);
        int numObservers = Integer.parseInt(args[5]);
        // Create map of server IDs to their addresses (excluding this server)
        Map<Long, InetSocketAddress> peerIDtoAddress = new ConcurrentHashMap<>();
        for (int i = 0; i < serverIDList.length; i++) {
            long id = Long.parseLong(serverIDList[i]);
            if (id != serverID) {
                String host = "localhost";
                int port = Integer.parseInt(udpPortList[i]);
                peerIDtoAddress.put(id, new InetSocketAddress(host, port));
            }
        }
        // Start PeerServerImpl
        try {
            PeerServerImpl server = new PeerServerImpl(udpPort, 0, serverID, peerIDtoAddress, gatewayID, numObservers);
            server.start();
            server.join();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
