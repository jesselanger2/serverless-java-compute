#!/bin/bash

# This script demonstrates the functionality of the GatewayServer and PeerServerImpl classes.

# HTTP endpoints:
# - POST /compileandrun: Compiles and runs Java code sent in the request body.
# - GET /leaderStatus: Returns the current leader status of the GatewayServer.
# - GET /summaryLog: Returns the summary log of the PeerServer.
# - GET /verboseLog: Returns the verbose log of the PeerServer.

# Redirect output to both console and output.log
exec > >(tee -i output.log)
# Redirect stderr so it doesn't get printed to console
exec 2>/dev/null

# Define constants
BUILD_CMD="mvn test"
NUM_SERVERS=7
NUM_OBSERVERS=1
GATEWAY_HTTP_PORT=8080
GATEWAY_PEER_PORT=8000
GATEWAY_ID=8
BASE_UDP_PORT=7000
HEARTBEAT_INTERVAL=2 # in seconds
GATEWAY_LEADER_STATUS_URL="http://localhost:${GATEWAY_HTTP_PORT}/leaderStatus"
REQUESTS_TO_SEND=9

# Function to generate Java code dynamically
generate_java_code() {
  local class_name="DynamicClass$1"
  echo "public class $class_name {
    public $class_name() {}
    public String run() { return \"Hello from $class_name!\"; }
  }"
}

# Build and run unit tests
#echo "Building the project and running tests..."
#$BUILD_CMD
#if [ $? -ne 0 ]; then
#  echo "Build or tests failed. Exiting."
#  exit 1
#fi

mvn clean compile

# Initialize server IDs and UDP port lists
SERVER_IDS=()
UDP_PORTS=()
for i in $(seq 1 $NUM_SERVERS); do
  SERVER_IDS+=($i)
  UDP_PORTS+=($((BASE_UDP_PORT + i * 3)))
done
# Add Gateway to server IDs and UDP port lists
SERVER_IDS+=("$GATEWAY_ID")
UDP_PORTS+=("$GATEWAY_PEER_PORT")
# Convert arrays to comma-separated strings
SERVER_ID_LIST=$(IFS=,; echo "${SERVER_IDS[*]}")
UDP_PORT_LIST=$(IFS=,; echo "${UDP_PORTS[*]}")

# Start Gateway server
echo "Starting GatewayServer..."
java -cp target/classes edu.yu.cs.com3800.stage5.GatewayServer $GATEWAY_HTTP_PORT $GATEWAY_PEER_PORT $GATEWAY_ID "$SERVER_ID_LIST" "$UDP_PORT_LIST" $NUM_OBSERVERS &
GATEWAY_PID=$!
echo "Started GatewayServer with PID $GATEWAY_PID on HTTP port $GATEWAY_HTTP_PORT and UDP port $GATEWAY_PEER_PORT"

# Start peer servers
echo "Starting $NUM_SERVERS peer servers..."
SERVER_PIDS=()
for i in $(seq 1 $NUM_SERVERS); do
  UDP_PORT=${UDP_PORTS[$i - 1]}
  java -cp target/classes edu.yu.cs.com3800.stage5.PeerServerImpl "$UDP_PORT" "$i" "$SERVER_ID_LIST" "$UDP_PORT_LIST" $GATEWAY_ID $NUM_OBSERVERS &
  PID=$!
  if [ $? -eq 0 ]; then
    SERVER_PIDS+=($PID)
    echo "Started PeerServer $i with PID $PID on UDP port $UDP_PORT"
  else
    echo "Failed to start PeerServer $i"
    exit 1
  fi
done

# Wait for leader election
echo "Waiting for leader election..."
LEADER_ELECTED=false
until [ "$LEADER_ELECTED" = true ]; do
  RESPONSE=$(curl -s $GATEWAY_LEADER_STATUS_URL)
  if [[ "$RESPONSE" == *"Leader"* ]]; then
    LEADER_ELECTED=true
    echo "Leader election complete: $RESPONSE"
  else
    sleep 1
  fi
done

# Send 9 unique client requests with generated Java code and print responses
echo "Sending $REQUESTS_TO_SEND client requests with dynamically generated Java code..."
for i in $(seq 1 $REQUESTS_TO_SEND); do
  JAVA_CODE=$(generate_java_code $i)
  RESPONSE=$(curl -s -X POST -H "Content-Type: text/x-java-source" http://localhost:$GATEWAY_HTTP_PORT/compileandrun -d "$JAVA_CODE")
  echo "Request $i:"
  echo "$JAVA_CODE"
  echo "Response:"
  echo "$RESPONSE"
  echo "-----------------------------------"

done

# Kill a follower
echo "Killing a follower node (PeerServer 2)..."
kill -9 "${SERVER_PIDS[1]}"
unset SERVER_PIDS[1]
sleep $(($HEARTBEAT_INTERVAL * 18))
echo "Node list after killing follower 2:"
curl -s $GATEWAY_LEADER_STATUS_URL

# Kill the leader
echo "Killing the leader node (PeerServer 7)..."
kill -9 "${SERVER_PIDS[5]}"
unset SERVER_PIDS[5]
sleep 1

# Send 9 more unique client requests with generated Java code in the background
echo "Sending $REQUESTS_TO_SEND unique client requests in the background..."
PIDS=()
for i in $(seq 1 $REQUESTS_TO_SEND); do
  JAVA_CODE=$(generate_java_code "Background$i")
  RESPONSE_FILE="response_Background$i.log"
  (curl -s -X POST -H "Content-Type: text/x-java-source" http://localhost:$GATEWAY_HTTP_PORT/compileandrun -d "$JAVA_CODE" > "$RESPONSE_FILE") &
  PIDS+=($!) # Capture the PID of the background process
  echo "Background request $i initiated. Response will be saved to $RESPONSE_FILE"
done

# Wait for each tracked PID
echo "Waiting for all background requests to finish..."
for PID in "${PIDS[@]}"; do
  wait "$PID" || echo "Process $PID failed."
done
echo "All background requests completed."

# Wait for new leader
echo "Waiting for new leader election..."
LEADER_ELECTED=false
until [ "$LEADER_ELECTED" = true ]; do
  RESPONSE=$(curl -s $GATEWAY_LEADER_STATUS_URL)
  if [[ "$RESPONSE" == *"Leader"* ]]; then
    LEADER_ELECTED=true
    echo "New leader elected: $RESPONSE" | head -n 1
  else
    sleep 1
  fi
done

# Print responses for background requests
echo "Responses for background requests:"
for i in $(seq 1 $REQUESTS_TO_SEND); do
  RESPONSE_FILE="response_Background$i.log"
  if [ -f "$RESPONSE_FILE" ]; then
    echo "Response for Background request $i:"
    cat "$RESPONSE_FILE"
    echo
    echo "-----------------------------------"
  else
    echo "Response file $RESPONSE_FILE not found!"
  fi
done

# Send one more unique request with Java code and print response
echo "Sending one more unique client request..."
JAVA_CODE=$(generate_java_code "Final")
RESPONSE=$(curl -s -X POST -H "Content-Type: text/x-java-source" http://localhost:$GATEWAY_HTTP_PORT/compileandrun -d "$JAVA_CODE")
echo "Final Request:"
echo "$JAVA_CODE"
echo "Response:"
echo "$RESPONSE"

# List Gossip message paths
echo "Gossip message file paths:"
for ID in $(seq 1 $NUM_SERVERS); do
  PORT="${UDP_PORTS[$ID - 1]}"
  echo "Summary logs for server $ID are located at: logs/summary_logs/PeerServerImpl-SUMMARY-ID-$ID-on-Port-$PORT-Log.txt"
  echo "Verbose logs for server $ID are located at: logs/verbose_logs/PeerServerImpl-VERBOSE-ID-$ID-on-Port-$PORT-Log.txt"
  echo "-----------------------------------"
done
echo "Summary logs for Gateway server are located at: logs/summary_logs/PeerServerImpl-SUMMARY-ID-$GATEWAY_ID-on-Port-$GATEWAY_PEER_PORT-Log.txt"
echo "Verbose logs for Gateway server are located at: logs/verbose_logs/PeerServerImpl-VERBOSE-ID-$GATEWAY_ID-on-Port-$GATEWAY_PEER_PORT-Log.txt"

# Shut down all servers
echo "Shutting down all servers..."
kill -9 $GATEWAY_PID
for PID in "${SERVER_PIDS[@]}"; do
  kill -9 "$PID"
done
echo "All servers shut down."

# Kill any remaining Java processes (in case of errors)
pkill java