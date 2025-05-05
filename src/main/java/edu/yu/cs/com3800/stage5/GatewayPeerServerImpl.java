package edu.yu.cs.com3800.stage5;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;

public class GatewayPeerServerImpl extends PeerServerImpl{

    public GatewayPeerServerImpl(int udpPort,
                                 long peerEpoch,
                                 Long serverID,
                                 Map<Long, InetSocketAddress> peerIDtoAddress,
                                 Long gatewayID,
                                 int numberOfObservers) throws IOException {
        super(udpPort, peerEpoch, serverID, peerIDtoAddress, gatewayID, numberOfObservers);
        setName("GatewayPeerServerImpl");
    }

    @Override
    public void setPeerState(ServerState newState) {
        if (newState != ServerState.OBSERVER) {
            throw new IllegalStateException("GatewayPeerServerImpl can only be in OBSERVER state.");
        }
        super.setPeerState(newState);
    }
}
