package edu.yu.cs.com3800.stage5;

import edu.yu.cs.com3800.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class Gossiper extends Thread implements LoggingServer, Serializable {

    private final Long ID;                                                  // server ID
    private transient final PeerServerImpl server;                          // server instance
    private final Map<Long, HeartbeatData> clusterHeartbeatData;            // heartbeat data for all nodes used for gossiping
    private transient final Map<Long, InetSocketAddress> peerIDtoAddress;   // map of peer IDs to their addresses
    private transient final LinkedBlockingQueue<Message> incomingMessages;  // queue of incoming messages
    private transient final Logger summaryLogger;                           // summary logger for this server
    private transient final Logger verboseLogger;                           // verbose logger for this server
    private volatile boolean shouldGossip;                                  // Flag to control gossiping
    private final ConcurrentLinkedQueue<Long> deadPeers;                    // list of dead peers

    static final int GOSSIP = 2000;         // gossip interval
    static final int FAIL = GOSSIP * 15;    // fail time to mark a peer as failed/dead
    static final int CLEANUP = FAIL * 2;    // cleanup time to stop tracking a failed peer

    public Gossiper(PeerServerImpl peerServer,
                    Map<Long, InetSocketAddress> peerIDtoAddress,
                    LinkedBlockingQueue<Message> incomingMessages,
                    Logger summaryLogger,
                    Logger verboseLogger) {
        setName("Gossiper");
        setDaemon(true);
        server = peerServer;
        ID = server.getServerId();
        clusterHeartbeatData = new ConcurrentHashMap<>();
        clusterHeartbeatData.put(ID, new HeartbeatData());
        this.peerIDtoAddress = peerIDtoAddress;
        this.incomingMessages = incomingMessages;
        this.summaryLogger = summaryLogger;
        this.verboseLogger = verboseLogger;
        shouldGossip = false;
        deadPeers = new ConcurrentLinkedQueue<>();
    }

    private class HeartbeatData implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;    // Add a serialVersionUID for serialization

        private int heartbeatCounter;
        private long lastUpdated;
        private boolean failed;
        private long failedTime;

        public HeartbeatData() {
            heartbeatCounter = 0;
            lastUpdated = System.currentTimeMillis();
            failed = false;
            failedTime = 0;
        }

        public void updateHeartbeat() {
            heartbeatCounter++;
            lastUpdated = System.currentTimeMillis();
        }

        public void setHeartbeat(int heartbeat) {
            heartbeatCounter = heartbeat;
            lastUpdated = System.currentTimeMillis();
        }
    
        public int getHeartbeatCounter() {
            return heartbeatCounter;
        }

        public long getLastUpdated() {
            return lastUpdated;
        }

        public void setFailed(long time) {
            failed = true;
            failedTime = time;
        }

        public boolean isFailed() {
            return failed;
        }

        public long getFailedTime() {
            return failedTime;
        }
    }

    @Override
    public void run() {
        // run gossiping on a scheduler so it can periodically send gossip messages
        try(ScheduledExecutorService ignored = scheduleGossips()) {
            // listen for gossip messages
            while (!isInterrupted()) {
                try {
                    Message message = incomingMessages.poll(1000, TimeUnit.MILLISECONDS);
                    if (message == null) {
                        continue;
                    }
                    if (server.isPeerDead(new InetSocketAddress(message.getSenderHost(), message.getSenderPort()))) {
                        continue;
                    }
                    // received gossip message
                    if (message.getMessageType() == Message.MessageType.GOSSIP) {
                        gossip(message);
                    }
                    // not a gossip message, add it back to the queue
                    else {
                        incomingMessages.add(message);
                    }
                    // check for failed peers and remove them
                    checkForFailedPeers();
                    removeFailedPeers();
                } catch (InterruptedException e) {
                    interrupt(); // Ensure interruption stops the thread
                } catch (IOException | ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private void gossip(Message message) throws IOException, ClassNotFoundException {
        Map<Long, HeartbeatData> receivedData = deserializeHeartbeatData(message.getMessageContents());
        InetSocketAddress sender = new InetSocketAddress(message.getSenderHost(), message.getSenderPort());
        long senderID = peerIDtoAddress.entrySet().stream()
                .filter(e -> e.getValue().equals(sender))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Sender is not a valid peer"));
        verboseLogger.info(server.getServerId() + ": received gossip message from " + senderID + " at node time " +
                System.currentTimeMillis() + " with contents:\n" + receivedData);
        // Update the clusterHeartbeatData map
        for (Map.Entry<Long, HeartbeatData> entry : receivedData.entrySet()) {
            long currentID = entry.getKey();
            // skip me
            if (currentID == this.ID) {
                continue;
            }
            // if the server is already dead, skip it
            if (deadPeers.contains(currentID)) {
                continue;
            }
            clusterHeartbeatData.putIfAbsent(currentID, new HeartbeatData());
            HeartbeatData localData = clusterHeartbeatData.get(currentID);
            int localHeartbeat = localData.getHeartbeatCounter();
            int currentHeartbeat = entry.getValue().getHeartbeatCounter();
            // peer records higher heartbeat for the current server; he's still alive, so update my table
            if (localHeartbeat < currentHeartbeat) {
                summaryLogger.info(server.getServerId() +  ": updated " + currentID + "’s heartbeat sequence to " +
                        currentHeartbeat + " based on message from " + senderID + " at node time " + System.currentTimeMillis());
                localData.setHeartbeat(currentHeartbeat);
            }
            // peer's record of current server's heartbeat hasn't changed, check if he's still alive
            else {
                long currentTime = System.currentTimeMillis();
                long lastUpdatedTime = localData.getLastUpdated();
                // current server has timed out, mark it as failed
                if (currentTime - lastUpdatedTime >= FAIL) {
                    localData.setFailed(currentTime);
                    server.reportFailedPeer(currentID);
                }
            }
        }
    }

    private void checkForFailedPeers() {
        // check if any peers have failed
        long currentTime = System.currentTimeMillis();
        clusterHeartbeatData.entrySet().stream()
                .filter(e -> !e.getKey().equals(ID))
                .filter(e -> !e.getValue().isFailed())
                .filter(e -> (currentTime - e.getValue().getLastUpdated()) >= FAIL)
                .forEach(e -> {
                    e.getValue().setFailed(currentTime);
                    server.reportFailedPeer(e.getKey());
                });
    }

    private void removeFailedPeers() {
        // remove peers if they have been failed for CLEANUP amount of time
        long currentTime = System.currentTimeMillis();
        clusterHeartbeatData.entrySet().stream()
                .filter(e -> !e.getKey().equals(ID))
                .filter(e -> e.getValue().isFailed())
                .filter(e -> (currentTime - e.getValue().getFailedTime()) >= CLEANUP)
                .forEach(e -> {
                    deadPeers.add(e.getKey());
                    clusterHeartbeatData.remove(e.getKey());
                });
    }

    private ScheduledExecutorService scheduleGossips() {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        // create gossip task
        Runnable gossip = () -> {
            // if server is in LOOKING state, skip gossiping
            if (!shouldGossip) {
                return;
            }
            // increase my heartbeat counter
            clusterHeartbeatData.get(ID).updateHeartbeat();
            long gossipTo = (int) (Math.random() * peerIDtoAddress.size());    // randomly choose peer server to send gossip to
            byte[] message;
            try {
                message = serializeHeartbeatData();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            // send gossip message to random peer
            server.sendMessage(Message.MessageType.GOSSIP, message, peerIDtoAddress.get(gossipTo));
        };
        scheduler.scheduleAtFixedRate(gossip, 0, GOSSIP, TimeUnit.MILLISECONDS);
        return scheduler;
    }

    public void setShouldGossip(boolean shouldGossip) {
        this.shouldGossip = shouldGossip;
    }

    private byte[] serializeHeartbeatData() throws IOException {
        Map<Long, HeartbeatData> heartbeatDataCopy = new HashMap<>(clusterHeartbeatData);
        try (ByteArrayOutputStream byteOutStream = new ByteArrayOutputStream();
             ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteOutStream)) {
            // serialize the map and return as byte[]
            objectOutputStream.writeObject(heartbeatDataCopy);
            return byteOutStream.toByteArray();
        }
    }

    private HashMap<Long, HeartbeatData> deserializeHeartbeatData(byte[] data) throws IOException, ClassNotFoundException {
        try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(data);
             ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream)) {
            // Deserialize the map
            return (HashMap<Long, HeartbeatData>) objectInputStream.readObject();
        }
    }

    public void shutdown() {
        interrupt();
    }
}
