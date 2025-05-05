package edu.yu.cs.com3800.stage5;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import edu.yu.cs.com3800.Message;
import edu.yu.cs.com3800.Util;

public class RoundRobinLeader extends Thread{

    private final PeerServerImpl server;                            // server this leader is running on
    private final LinkedBlockingQueue<Message> clientRequests;      // queue of messages received
    private final LinkedBlockingQueue<Message> workerResponses;     // queue of messages to send
    private final List<InetSocketAddress> workers;                  // list of workers
    private int nextWorker = 0;                                     // index of next worker to send message to
    private final Logger logger;

    public RoundRobinLeader(PeerServerImpl peerServer,
                            LinkedBlockingQueue<Message> clientRequests,
                            LinkedBlockingQueue<Message> workerResponses,
                            Map<Long, InetSocketAddress> peerIDtoAddress,
                            Long gatewayID) {
        setName("RoundRobinLeader " + peerServer.getServerId());
        setDaemon(true);
        this.server = peerServer;
        this.clientRequests = clientRequests;
        this.workerResponses = workerResponses;
        this.logger = Util.initializeLogging("RoundRobinLeader", peerServer.getServerId(), peerServer.getTcpPort(), "BASIC");
        // create list of workers
        workers = new ArrayList<>();
        for (InetSocketAddress address : peerIDtoAddress.values()) {
            // skip dead peers
            if (server.isPeerDead(address)) {
                continue;
            }
            // exclude gateway server
            InetSocketAddress gatewayAddress = peerIDtoAddress.get(gatewayID);
            if (gatewayAddress == null || address.equals(gatewayAddress)) {
                continue;
            }
            workers.add(address);
        }
    }

    @Override
    public void run() {
        logger.info("Starting RoundRobinLeader, creating executor service");
        // create executor service to send messages to workers
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() / 2);

        while (!isInterrupted()) {
            // get message from queue
            Message msg;
           try {
               msg = clientRequests.poll(3200, TimeUnit.MILLISECONDS);
           } catch (InterruptedException e) {
               return;
           }
            if (msg == null) {
                continue;   // if no message, continue waiting
            }
            if (msg.getMessageType() == Message.MessageType.WORK) {
                logger.info("Received message from TCPServer:\n" + msg);
                AtomicBoolean fail = new AtomicBoolean(true);
                while (fail.get()) {
                    fail.set(false);
                    // get next worker
                    InetSocketAddress worker = getNextWorker();
                    int workerTcpPort = worker.getPort() + 2;
                    submitTask(executor, worker, workerTcpPort, msg, fail);
                }
            }
        }
        logger.info("Exiting RoundRobinLeader");
    }

    private InetSocketAddress getNextWorker() {
        InetSocketAddress worker = workers.get(nextWorker);
        while (server.isPeerDead(worker)) {
            workers.remove(worker);
            worker = workers.get(nextWorker);
        }
        nextWorker = (nextWorker + 1) % workers.size(); // round-robin
        return worker;
    }

    private void submitTask(ExecutorService executor, InetSocketAddress worker, int workerTcpPort, Message msg, AtomicBoolean fail) {
        // connect to worker with TCP
        executor.submit(() -> {
            logger.info("Sending message to worker on port " + workerTcpPort);
            try(Socket socket = new Socket(worker.getHostName(), workerTcpPort)) {
                byte[] response = sendMessageToWorker(socket, msg);
                // if the peer server died while it was doing work, set fail and rerun task with another server
                if (server.isPeerDead(worker)) {
                    fail.set(true);
                    workers.remove(worker);
                    return null;
                }
                // send response to client
                Message responseMsg = new Message(response);
                logger.info("Received response from worker:\n" + new String(responseMsg.getMessageContents()) + "\nSending response to TCPServer");
                workerResponses.put(responseMsg);
            } catch (Exception e) {
                logger.severe("Error sending message to worker");
                //logger.severe(Util.getStackTrace(e));
                fail.set(true);
            }
            return null;
        });
    }

    private byte[] sendMessageToWorker(Socket socket, Message msg) throws IOException {
        // send message to worker
        OutputStream out = socket.getOutputStream();
        out.write(msg.getNetworkPayload());
        // read response from worker
        InputStream in = socket.getInputStream();
        return Util.readAllBytesFromNetwork(in);
    }

    public void shutdown() {
        interrupt();
    }
}
