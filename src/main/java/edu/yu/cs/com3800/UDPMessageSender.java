package edu.yu.cs.com3800;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

public class UDPMessageSender extends Thread implements LoggingServer {
    private LinkedBlockingQueue<Message> outgoingMessages;
    private Logger logger;
    private int serverUdpPort;

    public UDPMessageSender(LinkedBlockingQueue<Message> outgoingMessages, int serverUdpPort) {
        this.outgoingMessages = outgoingMessages;
        setDaemon(true);
        this.serverUdpPort = serverUdpPort;
        setName("UDPMessageSender-port-" + this.serverUdpPort);
        this.logger = Util.initializeLogging("UDPMessageSender", 0L, serverUdpPort, "BASIC");
    }

    public void shutdown() {
        interrupt();
    }

    public int getPort() {
        return this.serverUdpPort;
    }

    @Override
    public void run() {
        logger.info("Starting UDPMessageSender on port " + this.serverUdpPort);
        while (!this.isInterrupted()) {
            try {
                Message messageToSend = this.outgoingMessages.poll();
                if (messageToSend != null) {
                    logger.info("Sending message:\n" + messageToSend);
                    DatagramSocket socket = new DatagramSocket();
                    byte[] payload = messageToSend.getNetworkPayload();
                    DatagramPacket sendPacket = new DatagramPacket(payload, payload.length, new InetSocketAddress(messageToSend.getReceiverHost(), messageToSend.getReceiverPort()));
                    socket.send(sendPacket);
                    socket.close();
                    logger.info("Message sent:\n" + messageToSend);
                }
            }
            catch (IOException e) {
                logger.log(Level.WARNING, "Failed to send packet", e);
            }
        }
        logger.info("Exiting UDPMessageSender");
    }
}
