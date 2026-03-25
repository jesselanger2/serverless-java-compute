# Serverless Java Compute

A distributed, fault-tolerant, serverless service for compiling and executing Java code across a cluster of nodes.

## Overview

This system implements a distributed compute cluster that accepts Java source code via HTTP, compiles it on-the-fly, and executes it across a pool of worker nodes. The architecture is designed for fault tolerance — nodes communicate using a gossip protocol for failure detection, elect leaders through a quorum-based algorithm inspired by [Apache ZooKeeper's ZAB protocol](https://github.com/apache/zookeeper/blob/90f8d835e065ea12dddd8ed9ca20872a4412c78a/zookeeper-server/src/main/java/org/apache/zookeeper/server/quorum/FastLeaderElection.java#L913), and gracefully handle node failures including leader crashes.

## Architecture

```
                    ┌───────────────────────┐
                    │   HTTP Client (cURL)  │
                    └──────────┬────────────┘
                               │ POST /compileandrun
                               ▼
                    ┌───────────────────────┐
                    │    Gateway Server      │
                    │  (HTTP + Observer)     │
                    └──────────┬────────────┘
                               │ TCP
                               ▼
                    ┌───────────────────────┐
                    │   Leader (elected)    │
                    │  RoundRobinLeader     │
                    └──┬───────┬────────┬───┘
                       │       │        │
                  TCP  │  TCP  │   TCP  │
                       ▼       ▼        ▼
                    ┌─────┐ ┌─────┐ ┌─────┐
                    │ F1  │ │ F2  │ │ F3  │  ... Follower nodes
                    └─────┘ └─────┘ └─────┘
                     JavaRunnerFollower
```

### Components

| Component | Description |
|---|---|
| **GatewayServer** | HTTP entry point that accepts Java source code from clients, forwards requests to the elected leader via TCP, caches responses, and queues requests during leader transitions |
| **PeerServerImpl** | Core cluster node that participates in leader election, runs the gossip protocol, and transitions between `LOOKING`, `LEADING`, and `FOLLOWING` states |
| **LeaderElection** | Quorum-based leader election using a simplified ZAB algorithm with broadcast notifications, exponential backoff, and epoch tracking |
| **RoundRobinLeader** | Distributes incoming work requests across follower nodes in a round-robin fashion via TCP, automatically skipping dead peers |
| **JavaRunnerFollower** | Receives Java source code from the leader, compiles and executes it using `JavaRunner`, and returns results. Saves completed work when a leader dies for replay to the new leader |
| **Gossiper** | Implements the gossip protocol for failure detection using heartbeat counters with configurable `GOSSIP` (2s), `FAIL` (30s), and `CLEANUP` (60s) intervals |
| **JavaRunner** | Compiles Java source code at runtime using the `javax.tools.JavaCompiler` API and executes it via reflection. The submitted class must have a no-arg constructor and a `String run()` method |
| **TCPServer** | Handles TCP connections between the gateway and the leader, bridging client requests and worker responses |

### Failure Handling

- **Follower failure** — The gossiper detects the missing heartbeat, the leader removes the failed node from its worker pool, and work continues on surviving followers.
- **Leader failure** — The gossiper detects the failure and all nodes re-enter the `LOOKING` state to elect a new leader. Followers preserve any in-progress work and replay it to the new leader. The gateway queues incoming client requests until a new leader is elected.

## Prerequisites

- **Java 21** or later (JDK, not just JRE — required for runtime compilation)
- **Apache Maven** 3.6+

## Build

```bash
mvn clean compile
```

### Run Tests

```bash
mvn test
```

## Usage

### Starting the Cluster

Each node is started as a separate process. The included demo script (`demo5.sh`) automates this for a 7-node cluster with 1 gateway/observer:

```bash
./demo5.sh
```

#### Manual Startup

**Gateway Server:**

```bash
java -cp target/classes edu.yu.cs.com3800.stage5.GatewayServer \
  <httpPort> <peerPort> <gatewayID> <serverIDList> <udpPortList> <numObservers>
```

**Peer Server:**

```bash
java -cp target/classes edu.yu.cs.com3800.stage5.PeerServerImpl \
  <udpPort> <serverID> <serverIDList> <udpPortList> <gatewayID> <numObservers>
```

### Submitting Java Code

Send a `POST` request to the gateway's `/compileandrun` endpoint with `Content-Type: text/x-java-source`:

```bash
curl -s -X POST \
  -H "Content-Type: text/x-java-source" \
  http://localhost:8080/compileandrun \
  -d 'public class HelloWorld {
    public HelloWorld() {}
    public String run() {
      return "Hello, World!";
    }
  }'
```

The submitted class must:
1. Be a `public` class
2. Have a public no-arg constructor
3. Have a `public String run()` method

### HTTP Endpoints

| Endpoint | Method | Description |
|---|---|---|
| `/compileandrun` | `POST` | Submit Java source code for compilation and execution |
| `/leaderStatus` | `GET` | Returns the current leader and the role of each peer |
| `/summaryLog` | `GET` | Returns the summary log of a peer server *(per-node HTTP port)* |
| `/verboseLog` | `GET` | Returns the verbose log of a peer server *(per-node HTTP port)* |

### Logging

Logs are written to the `logs/` directory:

- `logs/summary_logs/` — High-level events (leader changes, heartbeat updates, failures)
- `logs/verbose_logs/` — Detailed gossip message contents and protocol-level events

## Project Structure

```
src/main/java/edu/yu/cs/com3800/
├── ElectionNotification.java    # Election vote notification DTO
├── JavaFileObjectFromString.java # In-memory Java source file for compiler
├── JavaRunner.java              # Runtime Java compilation and execution
├── LeaderElection.java          # ZAB-inspired leader election algorithm
├── LoggingServer.java           # Logging interface
├── Message.java                 # Network message serialization
├── PeerServer.java              # PeerServer interface and ServerState enum
├── UDPMessageReceiver.java      # UDP message listener
├── UDPMessageSender.java        # UDP message broadcaster
├── Util.java                    # Shared utilities
├── Vote.java                    # Leader vote record
└── stage5/
    ├── GatewayPeerServerImpl.java  # Gateway's peer server (OBSERVER role)
    ├── GatewayServer.java          # HTTP gateway and request router
    ├── Gossiper.java               # Gossip protocol and failure detection
    ├── JavaRunnerFollower.java      # Worker node that compiles/runs code
    ├── PeerServerImpl.java         # Core peer server implementation
    ├── RoundRobinLeader.java       # Leader work distribution
    └── TCPServer.java              # TCP connection handler
```
