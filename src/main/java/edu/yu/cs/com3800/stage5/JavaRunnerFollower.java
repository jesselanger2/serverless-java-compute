package edu.yu.cs.com3800.stage5;

import edu.yu.cs.com3800.JavaRunner;
import edu.yu.cs.com3800.Message;
import edu.yu.cs.com3800.Util;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

public class JavaRunnerFollower extends Thread {

    private final PeerServerImpl peerServer;
    private final Logger logger;
    private JavaRunner javaRunner;
    private final Queue<SavedWork> workForNewLeader;
    private long mostRecentLeaderID;

    private record SavedWork(String output, boolean error, Message message) {}

    public JavaRunnerFollower(PeerServerImpl peerServer) {
        setName("JavaRunnerFollower " + peerServer.getServerId());
        setDaemon(true);
        this.peerServer = peerServer;
        this.workForNewLeader = new LinkedBlockingQueue<>();
        this.mostRecentLeaderID = peerServer.getCurrentLeader().getProposedLeaderID();
        this.logger = Util.initializeLogging("JavaRunnerFollower", peerServer.getServerId(), peerServer.getTcpPort(), "BASIC");
        try {
            logger.info("Creating JavaRunner");
            this.javaRunner = new JavaRunner();
        } catch (Exception e) {
            logger.severe("Error creating JavaRunner");
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        logger.info("Follower " + peerServer.getServerId() + " is running, waiting for messages...");
        try(ServerSocket serverSocket = new ServerSocket(peerServer.getTcpPort())) {
            logger.info("Follower socket successfully opened on port " + peerServer.getTcpPort());
            while (!isInterrupted()) {
                Socket leaderSocket = serverSocket.accept();
                logger.info("Connected to leader + " + leaderSocket.getRemoteSocketAddress());
                // send saved work from precious leader to new leader
                while (!workForNewLeader.isEmpty()) {
                    // get saved work from previous leader and send to new leader
                    SavedWork savedWork = workForNewLeader.poll();
                    sendCompletedWorkToLeader(savedWork.message(), leaderSocket, savedWork.output(), savedWork.error());
                    // ensure most recent leader is up to date
                    mostRecentLeaderID = peerServer.getCurrentLeader().getProposedLeaderID();
                }
                // check if I am the new leader
                if (mostRecentLeaderID == peerServer.getServerId()) {
                    logger.info("I became the new leader, exiting JavaRunnerFollower...");
                    return;
                }
                // get new work from leader
                Message msg = getMessageFromLeader(leaderSocket);
                // exception occurred while getting message from leader, continue to next iteration
                if (msg == null) {
                    continue;
                }
                if (msg.getMessageType() == Message.MessageType.WORK) {
                    InputStream is = new ByteArrayInputStream(msg.getMessageContents());
                    String output;
                    boolean error = false;
                    try {
                        logger.info("Running JavaRunner");
                        output = javaRunner.compileAndRun(is);
                    } catch (IOException | ReflectiveOperationException e) {
                        logger.severe("Exception caught while running JavaRunner");
                        output = Util.getStackTrace(e);
                        error = true;
                    }
                    // if leader is dead, save work for new leader
                    if (peerServer.isPeerDead(mostRecentLeaderID)) {
                        logger.info("Leader is dead, saving work for new leader");
                        workForNewLeader.add(new SavedWork(output, error, msg));
                        continue;
                    }
                    // send completed work message to leader
                    sendCompletedWorkToLeader(msg, leaderSocket, output, error);
                }
            }
            logger.info("Exiting JavaRunnerFollower");
        } catch (IOException e) {
            logger.severe("Error running JavaRunnerFollower");
            throw new RuntimeException(e);
        }
    }

    private Message getMessageFromLeader(Socket leaderSocket) {
        InputStream in;
        try {
            in = leaderSocket.getInputStream();
        } catch (IOException e) {
            logger.severe("Error getting input stream from leader");
            return null;
        }
        byte[] message = Util.readAllBytesFromNetwork(in);
        Message msg = new Message(message);
        logger.info("Received message from leader:\n" + msg);
        return msg;
    }

    private void sendCompletedWorkToLeader(Message msg, Socket leaderSocket, String output, boolean error) {
        logger.info("Sending completed work message to leader");
        long requestID = msg.getRequestID();
        Message response = new Message(
                Message.MessageType.COMPLETED_WORK,
                output.getBytes(),
                peerServer.getAddress().getHostName(),
                peerServer.getTcpPort(),
                msg.getSenderHost(),
                msg.getSenderPort(),
                requestID,
                error);
        OutputStream out;
        try {
            out = leaderSocket.getOutputStream();
            out.write(response.getNetworkPayload());
        } catch (IOException e) {
            logger.severe("Error sending completed work message to leader");
            workForNewLeader.add(new SavedWork(output, error, msg));
        }
    }

    public void shutdown() {
        interrupt();
    }
}
