package edu.yu.cs.com3800;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.yu.cs.com3800.stage5.PeerServerImpl;

/**We are implemeting a simplfied version of the election algorithm. For the complete version which covers all possible scenarios, see https://github.com/apache/zookeeper/blob/90f8d835e065ea12dddd8ed9ca20872a4412c78a/zookeeper-server/src/main/java/org/apache/zookeeper/server/quorum/FastLeaderElection.java#L913
 */
public class LeaderElection {
    /**
     * time to wait once we believe we've reached the end of leader election.
     */
    private final static int finalizeWait = 2000;

    /**
     * Upper bound on the amount of time between two consecutive notification checks.
     * This impacts the amount of time to get the system up again after long partitions. Currently 30 seconds.
     */
    private final static int maxNotificationInterval = 30000;

    private final PeerServerImpl server;
    private final Long gatewayID;
    private final LinkedBlockingQueue<Message> incomingMessages;
    private final Logger logger;
    private long proposedLeader;
    private long proposedEpoch;

    public LeaderElection(PeerServerImpl server, Long gatewayID, LinkedBlockingQueue<Message> incomingMessages, Logger logger) {
        logger.log(Level.INFO, "LeaderElection created for server " + server.getServerId() + " with epoch " + server.getPeerEpoch());
        this.server = server;
        this.gatewayID = gatewayID;
        this.incomingMessages = incomingMessages;
        this.logger = logger;
        long serverId = server.getServerId();
        this.proposedLeader = serverId == gatewayID ? -1 : serverId;    // If this is the gateway, we don't propose a leader
        this.proposedEpoch = server.getPeerEpoch();
    }

    /**
     * Note that the logic in the comments below does NOT cover every last "technical" detail you will need to address to implement the election algorithm.
     * How you store all the relevant state, etc., are details you will need to work out.
     * @return the elected leader
     */
    public synchronized Vote lookForLeader() {
        logger.log(Level.INFO, "Starting leader election on server " + this.server.getServerId() + " with epoch " + this.server.getPeerEpoch());
        try {
            //send initial notifications to get things started
            logger.log(Level.INFO, "Sending initial notifications from server " + this.server.getServerId() + " with epoch " + this.server.getPeerEpoch());
            sendNotifications();
            //Map of votes received from other servers
            Map<Long, ElectionNotification> votesReceived = new HashMap<>();
            // Set the time at which we will check for notifications again
            int notificationInterval = finalizeWait;
            //Loop in which we exchange notifications with other servers until we find a leader
            logger.log(Level.INFO, "Entering election loop for server " + this.server.getServerId() + " with epoch " + this.server.getPeerEpoch());
            while(true){
                //Remove next notification from queue
                Message msg = this.incomingMessages.poll(notificationInterval, TimeUnit.MILLISECONDS);
                //If no notifications received...
                if (msg == null) {
                    logger.log(Level.INFO, "No messages received on server " + this.server.getServerId() + " with epoch " + this.server.getPeerEpoch());
                    logger.log(Level.INFO, "Resending notifications from server " + this.server.getServerId() + " with epoch " + this.server.getPeerEpoch());
                    //...resend notifications to prompt a reply from others
                    //...use exponential back-off when notifications not received but no longer than maxNotificationInterval...
                    sendNotifications();
                    notificationInterval = Math.min(notificationInterval * 2, maxNotificationInterval);
                }
                //If we did get a message...
                else {
                    logger.log(Level.INFO, "Received message:\n" + msg);
                    // if the message is from a dead server, ignore it
                    if (server.isPeerDead(new InetSocketAddress(msg.getSenderHost(), msg.getSenderPort()))) {
                        continue;
                    }
                    // if the message is a gossip, ignore it
                    if (msg.getMessageType() == Message.MessageType.GOSSIP) {
                        continue;
                    }
                    ElectionNotification n = getNotificationFromMessage(msg);
                    //...if it's for an earlier epoch, or from an observer, ignore it.
                    if (n.getState() == PeerServer.ServerState.OBSERVER || n.getSenderID() == gatewayID) {
                        logger.log(Level.INFO, "Ignoring message from observer");
                        continue;
                    }
                    if (n.getPeerEpoch() < this.server.getPeerEpoch()) {
                        logger.log(Level.INFO, "Ignoring message from earlier epoch");
                        continue;
                    }
                    // Update my vote count
                    votesReceived.put(n.getSenderID(), n);
                    //...if the received message has a vote for a leader which supersedes mine, change my vote (and send notifications to all other voters about my new vote).
                    //(Be sure to keep track of the votes I received and who I received them from.)
                    if (supersedesCurrentVote(n.getProposedLeaderID(), n.getPeerEpoch())) {
                        logger.log(Level.INFO, "Received message with higher ranked vote; updating vote and sending notifications");
                        this.proposedLeader = n.getProposedLeaderID();
                        this.proposedEpoch = n.getPeerEpoch();
                        sendNotifications();
                    }
                    //If I have enough votes to declare my currently proposed leader as the leader...
                    if (haveEnoughVotes(votesReceived, new Vote(this.proposedLeader, this.proposedEpoch))) {
                        logger.log(Level.INFO, "Have enough votes to declare leader; doing final check to see if there are any higher ranked votes");
                        //..do a last check to see if there are any new votes for a higher ranked possible leader. If there are, continue in my election "while" loop.
                        Thread.sleep(finalizeWait);
                        if (higherRankedVote()) {
                            logger.log(Level.INFO, "Higher ranked vote found; continuing election");
                            continue;
                        }
                        //If there are no new relevant messages from the reception queue, set my own state to either LEADING or FOLLOWING and RETURN the elected leader.
                        logger.log(Level.INFO, "No higher ranked votes found; accepting election winner");
                        return acceptElectionWinner(
                                new ElectionNotification(
                                        this.proposedLeader,
                                        null,
                                        this.server.getServerId(),
                                        this.proposedEpoch
                                )
                        );
                    }
                }
            }
        }
        catch (Exception e) {
            this.logger.log(Level.SEVERE,"Exception occurred during election; election canceled", e);
        }
        return null;
    }

    private boolean higherRankedVote() {
        // Process all messages received during finalizeWait
        Message newMessage;
        while ((newMessage = this.incomingMessages.poll()) != null) {
            ElectionNotification newNotification = getNotificationFromMessage(newMessage);
            // Ignore messages from earlier epochs or observers
            if (newNotification.getPeerEpoch() < this.server.getPeerEpoch() || newNotification.getState() == PeerServer.ServerState.OBSERVER) {
                continue;
            }
            // If a higher-ranked leader is found, continue the election
            if (supersedesCurrentVote(newNotification.getProposedLeaderID(), newNotification.getPeerEpoch())) {
                // Update my proposal and resend notifications
                this.proposedLeader = newNotification.getProposedLeaderID();
                this.proposedEpoch = newNotification.getPeerEpoch();
                sendNotifications();
                return true;
            }
        }
        return false;
    }

    private void sendNotifications() {
        ElectionNotification n = new ElectionNotification(
                this.proposedLeader,
                this.server.getPeerState(),
                this.server.getServerId(),
                this.proposedEpoch
        );
        byte[] msgContent = buildMsgContent(n);
        this.server.sendBroadcast(Message.MessageType.ELECTION, msgContent);
    }

    private Vote acceptElectionWinner(ElectionNotification n) {
        long serverId = this.server.getServerId();
        if (serverId == gatewayID) {
            this.server.setPeerState(PeerServer.ServerState.OBSERVER);
        }
        else if (serverId == this.proposedLeader) {
            this.server.setPeerState(PeerServer.ServerState.LEADING);
        }
        else {
            this.server.setPeerState(PeerServer.ServerState.FOLLOWING);
        }
        // Clear out the incoming queue before returning
        this.incomingMessages.clear();
        // Return the elected leader
        return new Vote(n.getProposedLeaderID(), n.getPeerEpoch());
    }

    /*
     * We return true if one of the following two cases hold:
     * 1- New epoch is higher
     * 2- New epoch is the same as current epoch, but server id is higher.
     */
    protected boolean supersedesCurrentVote(long newId, long newEpoch) {
        return (newEpoch > this.proposedEpoch) || ((newEpoch == this.proposedEpoch) && (newId > this.proposedLeader));
    }

    /**
     * Termination predicate. Given a set of votes, determines if we have sufficient support for the proposal to declare the end of the election round.
     * Who voted for who isn't relevant, we only care that each server has one current vote.
     */
    protected boolean haveEnoughVotes(Map<Long, ElectionNotification> votes, Vote proposal) {
        //is the number of votes for the proposal > the size of my peer server’s quorum?
        int votesForProposal = 0;
        for (ElectionNotification n : votes.values()) {
            if (n.getProposedLeaderID() == proposal.getProposedLeaderID() && n.getPeerEpoch() == proposal.getPeerEpoch()) {
                votesForProposal++;
            }
        }
        return votesForProposal >= this.server.getQuorumSize();
    }

    public static byte[] buildMsgContent(ElectionNotification notification) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES * 3 + Character.BYTES);  // 26 bytes total
        buffer.putLong(notification.getProposedLeaderID());
        buffer.putChar(notification.getState().getChar());
        buffer.putLong(notification.getSenderID());
        buffer.putLong(notification.getPeerEpoch());
        return buffer.array();  // Return the resulting byte array
    }

    public static ElectionNotification getNotificationFromMessage(Message received) {
        byte[] content = received.getMessageContents();
        ByteBuffer buffer = ByteBuffer.wrap(content);
        // Ensure we have enough bytes to read all required fields
        if (buffer.remaining() < Long.BYTES * 3 + Character.BYTES) {
            throw new IllegalArgumentException("Insufficient bytes in message to construct ElectionNotification");
        }
        // Extract leader ID
        long leaderID = buffer.getLong();
        // Extract the state
        char stateChar = buffer.getChar();
        PeerServer.ServerState state = PeerServer.ServerState.getServerState(stateChar);
        // Extract sender ID
        long senderID = buffer.getLong();
        // Extract peer epoch
        long peerEpoch = buffer.getLong();

        return new ElectionNotification(leaderID, state, senderID, peerEpoch);
    }
}