package edu.yu.cs.com3800.stage5;

import edu.yu.cs.com3800.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class Stage5Test {

    AtomicInteger nextPort = new AtomicInteger(8000);
    AtomicInteger nextHttpPort = new AtomicInteger(10000);

    private final String hw =
            """
            package edu.yu.cs.fall2019.com3800.stage1;
            
            public class HelloWorld {
            
                public String run() {
                    return "Hello world!";
                }
            }
            """;

    private final String loop =
            """
            public class Loop {
            
                public Loop() {}
                
                public String run() {
                    String result = "";
                    for (int i = 0; i < 3; i++) {
                        result += "A";
                    }
                    return result;
                }
            }
            """;

    private final String name =
            """
            public class Name {
            
                public Name() {}
                
                public String run() {
                    String name = "Jesse";
                    return "Hello, " + name;
                }
            }
            """;

    @AfterEach
    public void pauseBetweenTests() throws InterruptedException {
        Thread.sleep(3000);
    }

    private ConcurrentHashMap<Long, InetSocketAddress> createThreePeerIDtoAddress() {
        // Create IDs and addresses
        ConcurrentHashMap<Long, InetSocketAddress> peerIDtoAddress = new ConcurrentHashMap<>();
        peerIDtoAddress.put(2L, new InetSocketAddress("localhost", nextPort.getAndAdd(10)));
        peerIDtoAddress.put(3L, new InetSocketAddress("localhost", nextPort.getAndAdd(10)));
        peerIDtoAddress.put(4L, new InetSocketAddress("localhost", nextPort.getAndAdd(10)));
        return peerIDtoAddress;
    }

    private ConcurrentHashMap<Long, InetSocketAddress> createFivePeerIDtoAddress() {
        // Create IDs and addresses
        ConcurrentHashMap<Long, InetSocketAddress> peerIDtoAddress = new ConcurrentHashMap<>();
        peerIDtoAddress.put(2L, new InetSocketAddress("localhost", nextPort.getAndAdd(10)));
        peerIDtoAddress.put(3L, new InetSocketAddress("localhost", nextPort.getAndAdd(10)));
        peerIDtoAddress.put(4L, new InetSocketAddress("localhost", nextPort.getAndAdd(10)));
        peerIDtoAddress.put(5L, new InetSocketAddress("localhost", nextPort.getAndAdd(10)));
        peerIDtoAddress.put(6L, new InetSocketAddress("localhost", nextPort.getAndAdd(10)));
        return peerIDtoAddress;
    }

    private ConcurrentHashMap<Long, InetSocketAddress> createTenPeerIDtoAddress() {
        // Create IDs and addresses
        ConcurrentHashMap<Long, InetSocketAddress> peerIDtoAddress = new ConcurrentHashMap<>();
        peerIDtoAddress.put(2L, new InetSocketAddress("localhost", nextPort.getAndAdd(10)));
        peerIDtoAddress.put(3L, new InetSocketAddress("localhost", nextPort.getAndAdd(10)));
        peerIDtoAddress.put(4L, new InetSocketAddress("localhost", nextPort.getAndAdd(10)));
        peerIDtoAddress.put(5L, new InetSocketAddress("localhost", nextPort.getAndAdd(10)));
        peerIDtoAddress.put(6L, new InetSocketAddress("localhost", nextPort.getAndAdd(10)));
        peerIDtoAddress.put(7L, new InetSocketAddress("localhost", nextPort.getAndAdd(10)));
        peerIDtoAddress.put(8L, new InetSocketAddress("localhost", nextPort.getAndAdd(10)));
        peerIDtoAddress.put(9L, new InetSocketAddress("localhost", nextPort.getAndAdd(10)));
        peerIDtoAddress.put(10L, new InetSocketAddress("localhost", nextPort.getAndAdd(10)));
        peerIDtoAddress.put(11L, new InetSocketAddress("localhost", nextPort.getAndAdd(10)));
        return peerIDtoAddress;
    }

    private List<PeerServerImpl> createThreeServers(Long gatewayID, GatewayServer gateway, int nObservers, ConcurrentHashMap<Long, InetSocketAddress> peerIDtoAddress) throws IOException {
        // Create servers
        ArrayList<PeerServerImpl> servers = new ArrayList<>();
        for (Map.Entry<Long, InetSocketAddress> entry : peerIDtoAddress.entrySet()) {
            if (entry.getKey().equals(gatewayID)) continue;
            HashMap<Long, InetSocketAddress> map = new HashMap<>(peerIDtoAddress);
            map.remove(entry.getKey());
            PeerServerImpl server = new PeerServerImpl(entry.getValue().getPort(), 0, entry.getKey(), map, gatewayID, nObservers);
            servers.add(server);
            new Thread(server, "Server on port " + server.getAddress().getPort()).start();
        }
        gateway.start();
        // Wait for threads to start and election to complete
        try {
            Thread.sleep(4500);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return servers;
    }

    private List<PeerServerImpl> createFiveServers(Long gatewayID, GatewayServer gateway, int nObservers, ConcurrentHashMap<Long, InetSocketAddress> peerIDtoAddress) throws IOException {
        // Create servers
        ArrayList<PeerServerImpl> servers = new ArrayList<>();
        for (Map.Entry<Long, InetSocketAddress> entry : peerIDtoAddress.entrySet()) {
            if (entry.getKey().equals(gatewayID)) continue;
            HashMap<Long, InetSocketAddress> map = new HashMap<>(peerIDtoAddress);
            map.remove(entry.getKey());
            PeerServerImpl server = new PeerServerImpl(entry.getValue().getPort(), 0, entry.getKey(), map, gatewayID, nObservers);
            servers.add(server);
            new Thread(server, "Server on port " + server.getAddress().getPort()).start();
        }
        gateway.start();

        // Wait for threads to start and election to complete
        try {
            Thread.sleep(4500);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return servers;
    }

    private List<PeerServerImpl> createTenServers(Long gatewayID, GatewayServer gateway, int nObservers, ConcurrentHashMap<Long, InetSocketAddress> peerIDtoAddress) throws IOException {
        // Create servers
        ArrayList<PeerServerImpl> servers = new ArrayList<>();
        for (Map.Entry<Long, InetSocketAddress> entry : peerIDtoAddress.entrySet()) {
            if (entry.getKey().equals(gatewayID)) continue;
            HashMap<Long, InetSocketAddress> map = new HashMap<>(peerIDtoAddress);
            map.remove(entry.getKey());
            PeerServerImpl server = new PeerServerImpl(entry.getValue().getPort(), 0, entry.getKey(), map, gatewayID, nObservers);
            servers.add(server);
            new Thread(server, "Server on port " + server.getAddress().getPort()).start();
        }
        gateway.start();

        // Wait for threads to start and election to complete
        try {
            Thread.sleep(6000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return servers;
    }

    // Test serialization and deserialization of ElectionNotification
    @Test
    public void testSerializationDeserialization() {
        ElectionNotification original = new ElectionNotification(
                1L, PeerServer.ServerState.LEADING, 2L, 3L);

        byte[] serialized = LeaderElection.buildMsgContent(original);
        Message message = new Message(
                Message.MessageType.ELECTION, serialized, "localhost", 6000, "localhost", 6020);
        ElectionNotification deserialized = LeaderElection.getNotificationFromMessage(message);

        assertEquals(original.getProposedLeaderID(), deserialized.getProposedLeaderID());
        assertEquals(original.getState(), deserialized.getState());
        assertEquals(original.getSenderID(), deserialized.getSenderID());
        assertEquals(original.getPeerEpoch(), deserialized.getPeerEpoch());
    }

    // Test leader election with three servers
    @Test
    public void testThreePeerServersElection() throws IOException {
        ConcurrentHashMap<Long, InetSocketAddress> peerIDtoAddress = createThreePeerIDtoAddress();
        int httpPort = nextHttpPort.getAndIncrement();
        int port = nextHttpPort.getAndAdd(9);
        GatewayServer gateway = new GatewayServer(httpPort, port, 0, 6L, peerIDtoAddress, 1);
        ConcurrentHashMap<Long, InetSocketAddress> map = new ConcurrentHashMap<>(peerIDtoAddress);
        map.put(6L, new InetSocketAddress("localhost", port));
        List<PeerServerImpl> servers = createThreeServers(6L, gateway,  1, map);

        // Get leaders
        Vote leader1 = servers.get(0).getCurrentLeader();
        Vote leader2 = servers.get(1).getCurrentLeader();
        Vote leader3 = servers.get(2).getCurrentLeader();
        Vote gatewayLeader = gateway.getLeader();

        assertNotNull(leader1);
        assertNotNull(leader2);
        assertNotNull(leader3);
        assertNotNull(gatewayLeader);
        assertEquals(leader1.getProposedLeaderID(), leader2.getProposedLeaderID());
        assertEquals(leader2.getProposedLeaderID(), leader3.getProposedLeaderID());
        assertEquals(leader3.getProposedLeaderID(), gatewayLeader.getProposedLeaderID());

        // Shutdown servers
        for (PeerServer server : servers) {
            server.shutdown();
        }
        gateway.shutdown();
    }

    // Test leader election with five servers
    @Test
    public void testFivePeerServersElection() throws IOException {
        ConcurrentHashMap<Long, InetSocketAddress> peerIDtoAddress = createFivePeerIDtoAddress();
        int httpPort = nextHttpPort.getAndIncrement();
        int port = nextHttpPort.getAndAdd(9);
        GatewayServer gateway = new GatewayServer(httpPort, port, 0, 1L, peerIDtoAddress, 1);
        ConcurrentHashMap<Long, InetSocketAddress> map = new ConcurrentHashMap<>(peerIDtoAddress);
        map.put(1L, new InetSocketAddress("localhost", port));
        List<PeerServerImpl> servers = createFiveServers(1L, gateway, 1, map);

        // Verify that all servers have the same leader
        Vote leader = null;
        Vote gatewayLeader = gateway.getLeader();
        assertNotNull(gatewayLeader);
        for (PeerServer server : servers) {
            Vote currentLeader = server.getCurrentLeader();
            assertNotNull(currentLeader);
            if (leader == null) {
                leader = currentLeader;
            } else {
                assertEquals(leader.getProposedLeaderID(), currentLeader.getProposedLeaderID());
                assertEquals(leader.getProposedLeaderID(), gatewayLeader.getProposedLeaderID());
            }
        }

        // Shutdown servers
        for (PeerServer server : servers) {
            server.shutdown();
        }
        gateway.shutdown();
    }

    @Test
    public void testTenPeerServersElection() throws IOException {
        ConcurrentHashMap<Long, InetSocketAddress> peerIDtoAddress = createTenPeerIDtoAddress();
        int httpPort = nextHttpPort.getAndIncrement();
        int port = nextHttpPort.getAndAdd(9);
        GatewayServer gateway = new GatewayServer(httpPort, port, 0, 1L, peerIDtoAddress, 1);
        ConcurrentHashMap<Long, InetSocketAddress> map = new ConcurrentHashMap<>(peerIDtoAddress);
        map.put(1L, new InetSocketAddress("localhost", port));
        List<PeerServerImpl> servers = createTenServers(1L, gateway, 1, map);

        // Verify that all servers have the same leader
        Vote leader = null;
        Vote gatewayLeader = gateway.getLeader();
        assertNotNull(gatewayLeader);
        for (PeerServer server : servers) {
            Vote currentLeader = server.getCurrentLeader();
            assertNotNull(currentLeader);
            if (leader == null) {
                leader = currentLeader;
            } else {
                assertEquals(leader.getProposedLeaderID(), currentLeader.getProposedLeaderID());
                assertEquals(leader.getProposedLeaderID(), gatewayLeader.getProposedLeaderID());
            }
        }

        // Shutdown servers
        for (PeerServer server : servers) {
            server.shutdown();
        }
        gateway.shutdown();
    }

    // Test server state transitions after election
    @Test
    public void testServerStateTransitions() throws IOException {
        ConcurrentHashMap<Long, InetSocketAddress> peerIDtoAddress = createThreePeerIDtoAddress();
        int httpPort = nextHttpPort.getAndIncrement();
        int port = nextHttpPort.getAndAdd(9);
        GatewayServer gateway = new GatewayServer(httpPort, port, 0, 1L, peerIDtoAddress, 1);
        ConcurrentHashMap<Long, InetSocketAddress> map = new ConcurrentHashMap<>(peerIDtoAddress);
        map.put(1L, new InetSocketAddress("localhost", port));
        List<PeerServerImpl> servers = createThreeServers(1L, gateway, 1, map);

        // Verify server states
        for (PeerServerImpl server : servers) {
            Vote currentLeader = server.getCurrentLeader();
            assertNotNull(currentLeader);
            if (server.getServerId().equals(currentLeader.getProposedLeaderID())) {
                assertEquals(PeerServer.ServerState.LEADING, server.getPeerState());
            } else {
                assertEquals(PeerServer.ServerState.FOLLOWING, server.getPeerState());
            }
        }
        assertNotNull(gateway.getLeader());

        // Shutdown servers
        for (PeerServer server : servers) {
            server.shutdown();
        }
        gateway.shutdown();
    }

    // Test leader election with a server down during the election
    @Test
    public void testServerDownDuringElection() throws IOException {
        ConcurrentHashMap<Long, InetSocketAddress> peerIDtoAddress = createThreePeerIDtoAddress();
        int httpPort = nextHttpPort.getAndIncrement();
        int port = nextHttpPort.getAndAdd(9);
        GatewayServer gateway = new GatewayServer(httpPort, port, 0, 1L, peerIDtoAddress, 1);
        ConcurrentHashMap<Long, InetSocketAddress> map = new ConcurrentHashMap<>(peerIDtoAddress);
        map.put(1L, new InetSocketAddress("localhost", port));
        List<PeerServerImpl> servers = createThreeServers(1L, gateway, 1, map);

        // Simulate a server going down during the election
        try {
            Thread.sleep(3500); // Wait for election to start
            servers.get(2).shutdown(); // Shutdown the third server
            Thread.sleep(3500); // Wait for election to complete
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        // Verify that remaining servers have elected a leader
        Vote leader1 = servers.get(0).getCurrentLeader();
        Vote leader2 = servers.get(1).getCurrentLeader();
        Vote gatewayLeader = gateway.getLeader();

        assertNotNull(leader1);
        assertNotNull(leader2);
        assertNotNull(gatewayLeader);
        assertEquals(leader1.getProposedLeaderID(), leader2.getProposedLeaderID());
        assertEquals(leader2.getProposedLeaderID(), gatewayLeader.getProposedLeaderID());

        // Shutdown remaining servers
        servers.get(0).shutdown();
        servers.get(1).shutdown();
        gateway.shutdown();
    }

    @Test
    public void testHelloWorldTwice() throws IOException, InterruptedException {
        ConcurrentHashMap<Long, InetSocketAddress> peerIDtoAddress = createThreePeerIDtoAddress();
        int httpPort = nextHttpPort.getAndIncrement();
        int port = nextHttpPort.getAndAdd(9);
        GatewayServer gateway = new GatewayServer(httpPort, port, 0, 1L, peerIDtoAddress, 1);
        ConcurrentHashMap<Long, InetSocketAddress> map = new ConcurrentHashMap<>(peerIDtoAddress);
        map.put(1L, new InetSocketAddress("localhost", port));
        List<PeerServerImpl> servers = createThreeServers(1L, gateway, 1, map);

        // connect to gateway through HTTP
        String host = "localhost";
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("http://%s:%d/compileandrun", host, httpPort)))
                .header("Content-Type", "text/x-java-source")
                .POST(HttpRequest.BodyPublishers.ofString(this.hw))
                .build();
        String response = client.send(request, HttpResponse.BodyHandlers.ofString()).body();

        assertEquals("Hello world!", response);

        HttpRequest request2 = HttpRequest.newBuilder()
                .uri(URI.create(String.format("http://%s:%d/compileandrun", host, httpPort)))
                .header("Content-Type", "text/x-java-source")
                .POST(HttpRequest.BodyPublishers.ofString(this.hw))
                .build();
        String response2 = client.send(request2, HttpResponse.BodyHandlers.ofString()).body();

        assertEquals("Hello world!", response2);

        // Shutdown servers
        for (PeerServer server : servers) server.shutdown();
        gateway.shutdown();
    }

    @Test
    public void testMultipleClientsTenRequests() throws IOException, InterruptedException {
        ConcurrentHashMap<Long, InetSocketAddress> peerIDtoAddress = createFivePeerIDtoAddress();
        int httpPort = nextHttpPort.getAndIncrement();
        int port = nextHttpPort.getAndAdd(9);
        GatewayServer gateway = new GatewayServer(httpPort, port, 0, 1L, peerIDtoAddress, 1);
        ConcurrentHashMap<Long, InetSocketAddress> map = new ConcurrentHashMap<>(peerIDtoAddress);
        map.put(1L, new InetSocketAddress("localhost", port));
        List<PeerServerImpl> servers = createFiveServers(1L, gateway, 1, map);

        List<String> responses = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String src = switch (i % 3) {
                case 0 -> this.hw;
                case 1 -> this.loop;
                case 2 -> this.name;
                default -> throw new IllegalStateException("Unexpected value: " + i);
            };
            // connect to gateway through HTTP
            String host = "localhost";
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(String.format("http://%s:%d/compileandrun", host, httpPort)))
                    .header("Content-Type", "text/x-java-source")
                    .POST(HttpRequest.BodyPublishers.ofString(src))
                    .build();
            responses.add(client.send(request, HttpResponse.BodyHandlers.ofString()).body());
        }

        // Validate responses
        assertEquals("Hello world!", responses.get(0));
        assertEquals("AAA", responses.get(1));
        assertEquals("Hello, Jesse", responses.get(2));
        assertEquals("Hello world!", responses.get(3));
        assertEquals("AAA", responses.get(4));
        assertEquals("Hello, Jesse", responses.get(5));
        assertEquals("Hello world!", responses.get(6));
        assertEquals("AAA", responses.get(7));
        assertEquals("Hello, Jesse", responses.get(8));
        assertEquals("Hello world!", responses.get(9));

        // Shutdown servers
        for (PeerServer server : servers) server.shutdown();
        gateway.shutdown();
    }

    @Test
    public void testSameClientFiveRequests() throws IOException, InterruptedException {
        ConcurrentHashMap<Long, InetSocketAddress> peerIDtoAddress = createFivePeerIDtoAddress();
        int httpPort = nextHttpPort.getAndIncrement();
        int port = nextHttpPort.getAndAdd(9);
        GatewayServer gateway = new GatewayServer(httpPort, port, 0, 1L, peerIDtoAddress, 1);
        ConcurrentHashMap<Long, InetSocketAddress> map = new ConcurrentHashMap<>(peerIDtoAddress);
        map.put(1L, new InetSocketAddress("localhost", port));
        List<PeerServerImpl> servers = createFiveServers(1L, gateway, 1, map);

        // connect to gateway through HTTP
        String host = "localhost";
        HttpClient client = HttpClient.newHttpClient();
        List<String> responses = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String src = switch (i % 3) {
                case 0 -> this.hw;
                case 1 -> this.loop;
                case 2 -> this.name;
                default -> throw new IllegalStateException("Unexpected value: " + i);
            };
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(String.format("http://%s:%d/compileandrun", host, httpPort)))
                    .header("Content-Type", "text/x-java-source")
                    .POST(HttpRequest.BodyPublishers.ofString(src))
                    .build();
            responses.add(client.send(request, HttpResponse.BodyHandlers.ofString()).body());
        }

        // Validate responses
        assertEquals("Hello world!", responses.get(0));
        assertEquals("AAA", responses.get(1));
        assertEquals("Hello, Jesse", responses.get(2));
        assertEquals("Hello world!", responses.get(3));
        assertEquals("AAA", responses.get(4));

        // Shutdown servers
        for (PeerServer server : servers) server.shutdown();
        gateway.shutdown();
    }

    @Test
    public void testHttpLogEndpoints() throws IOException {
        ConcurrentHashMap<Long, InetSocketAddress> peerIDtoAddress = createThreePeerIDtoAddress();
        int httpPort = nextHttpPort.getAndIncrement();
        int port = nextHttpPort.getAndAdd(9);
        GatewayServer gateway = new GatewayServer(httpPort, port, 0, 6L, peerIDtoAddress, 1);
        ConcurrentHashMap<Long, InetSocketAddress> map = new ConcurrentHashMap<>(peerIDtoAddress);
        map.put(6L, new InetSocketAddress("localhost", port));
        List<PeerServerImpl> servers = createThreeServers(6L, gateway,  1, map);

        try {
            // Test the /summaryLog endpoint
            String summaryLogResponse = sendGetRequest("http://localhost:8013/summaryLog");
            System.out.println("Summary Log Response:");
            System.out.println(summaryLogResponse + "\n");

            // Test the /verboseLog endpoint
            String verboseLogResponse = sendGetRequest("http://localhost:8013/verboseLog");
            System.out.println("Verbose Log Response:");
            System.out.println(verboseLogResponse);
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Shutdown servers
        for (PeerServer server : servers) {
            server.shutdown();
        }
        gateway.shutdown();
    }

    private String sendGetRequest(String urlString) throws IOException {
        URL url = URI.create(urlString).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");

        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String inputLine;
            StringBuilder content = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                content.append(inputLine);
            }
            in.close();
            return content.toString();
        } else {
            return "GET request failed with response code: " + responseCode;
        }
    }

    @Test
    public void testLeaderStatusHttpEndpoint() throws IOException {
        ConcurrentHashMap<Long, InetSocketAddress> peerIDtoAddress = createThreePeerIDtoAddress();
        int httpPort = nextHttpPort.getAndIncrement();
        int port = nextHttpPort.getAndAdd(9);
        GatewayServer gateway = new GatewayServer(httpPort, port, 0, 6L, peerIDtoAddress, 1);
        ConcurrentHashMap<Long, InetSocketAddress> map = new ConcurrentHashMap<>(peerIDtoAddress);
        map.put(6L, new InetSocketAddress("localhost", port));
        List<PeerServerImpl> servers = createThreeServers(6L, gateway,  1, map);

        try {
            // Test the /leaderStatus endpoint
            String urlString = "http://localhost:" + httpPort + "/leaderStatus";
            String leaderStatusResponse = sendGetRequest(urlString);
            System.out.println("Leader Status Response:\n" + leaderStatusResponse);
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Shutdown servers
        for (PeerServer server : servers) {
            server.shutdown();
        }
        gateway.shutdown();
    }

    @Test
    public void failureDetectionDuringRequests() throws IOException, InterruptedException {
        ConcurrentHashMap<Long, InetSocketAddress> peerIDtoAddress = createTenPeerIDtoAddress();
        GatewayServer gateway = new GatewayServer(8000, 8001, 0, 1L, peerIDtoAddress, 1);
        ConcurrentHashMap<Long, InetSocketAddress> map = new ConcurrentHashMap<>(peerIDtoAddress);
        map.put(1L, new InetSocketAddress("localhost", 8001));
        List<PeerServerImpl> servers = createTenServers(1L, gateway, 1, map);

        // connect to gateway through HTTP
        int httpPort = gateway.getHttpPort();
        String host = "localhost";
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("http://%s:%d/compileandrun", host, httpPort)))
                .header("Content-Type", "text/x-java-source")
                .POST(HttpRequest.BodyPublishers.ofString(this.hw))
                .build();
        String response = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        assertEquals("Hello world!", response);

        // Simulate killing a couple of workers
        servers.remove(2).shutdown();
        servers.remove(3).shutdown();
        Thread.sleep(3500); // Wait for failure detection

        // Submit more requests
        HttpRequest request2 = HttpRequest.newBuilder()
                .uri(URI.create(String.format("http://%s:%d/compileandrun", host, httpPort)))
                .header("Content-Type", "text/x-java-source")
                .POST(HttpRequest.BodyPublishers.ofString(this.loop))
                .build();
        String response2 = client.send(request2, HttpResponse.BodyHandlers.ofString()).body();
        assertEquals("AAA", response2);

        // Simulate killing the leader
        PeerServerImpl leaderServer = servers.stream()
                .filter(server -> server.getPeerState() == PeerServer.ServerState.LEADING)
                .findFirst()
                .orElseThrow();
        servers.remove(leaderServer);
        leaderServer.shutdown();
        Thread.sleep(5500); // Wait for new election

        // Submit another request
        HttpRequest request3 = HttpRequest.newBuilder()
                .uri(URI.create(String.format("http://%s:%d/compileandrun", host, httpPort)))
                .header("Content-Type", "text/x-java-source")
                .POST(HttpRequest.BodyPublishers.ofString(this.name))
                .build();
        String response3 = client.send(request3, HttpResponse.BodyHandlers.ofString()).body();
        assertEquals("Hello, Jesse", response3);

        // Shutdown remaining servers
        for (PeerServer server : servers) server.shutdown();
        gateway.shutdown();
    }
}
