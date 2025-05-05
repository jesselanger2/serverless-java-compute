package edu.yu.cs.com3800.stage5;

import edu.yu.cs.com3800.Message;
import edu.yu.cs.com3800.Util;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public class TCPServer extends Thread {

    private final int port;                                     // port that this TCPServer is running on
    private final PeerServerImpl peerServer;                    // leader server that this TCPServer is running on
    private final LinkedBlockingQueue<Message> clientRequests;  // queue of messages to send to RoundRobinLeader
    private final LinkedBlockingQueue<Message> workerResponses; // queue of responses from RoundRobinLeader
    private final Logger logger;

    public TCPServer(int port,
                     PeerServerImpl peerServer,
                     LinkedBlockingQueue<Message> clientRequests,
                     LinkedBlockingQueue<Message> workerResponses) {
        setName("TCPServer");
        setDaemon(true);
        this.port = port;
        this.peerServer = peerServer;
        this.logger = Util.initializeLogging("TCPServer", peerServer.getServerId(), port, "BASIC");
        this.clientRequests = clientRequests;
        this.workerResponses = workerResponses;
    }

    @Override
    public void run() {
        logger.info("Starting TCP server on port " + port);
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (!isInterrupted()) {
                // Accept client connection
                Socket clientSocket = serverSocket.accept();
                // Read message from client
                InputStream in = clientSocket.getInputStream();
                byte[] requestIDBytes = new byte[8];
                in.read(requestIDBytes);
                long requestID = ByteBuffer.wrap(requestIDBytes).getLong();
                byte[] message = Util.readAllBytesFromNetwork(in);
                logger.info("Received message from client:\n" + new String(message));
                // Build message and send to RoundRobinLeader (via clientRequests)
                Message msg = new Message(
                        Message.MessageType.WORK,
                        message,
                        clientSocket.getInetAddress().getHostAddress(),
                        clientSocket.getPort(),
                        peerServer.getAddress().getHostName(),
                        port,
                        requestID
                );
                logger.info("Handing message to RoundRobinLeader");
                clientRequests.put(msg);
                // Get response from RoundRobinLeader and send to client
                Message response = workerResponses.take();
                if (response.getMessageType() == Message.MessageType.COMPLETED_WORK) {
                    logger.info("Received response from RoundRobinLeader:\n" + new String(response.getMessageContents()) + "\nSending response to client");
                    clientSocket.getOutputStream().write(response.getNetworkPayload());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        logger.info("Exiting TCP server on port " + port);
    }

    public void shutdown() {
        interrupt();
    }
}
