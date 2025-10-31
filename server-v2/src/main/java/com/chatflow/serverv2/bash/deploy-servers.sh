#!/bin/bash

# Your actual information
KEY_FILE="6650-assignment1-key.pem"
ACCOUNT_ID="590183786772"
REGION="us-west-2"

# Server IPs
declare -a SERVER_IPS=(
  "54.190.193.216"
  "34.222.153.85"
  "34.222.6.165"
  "35.91.154.66"
)

echo "=========================================="
echo "Deploying server-v2 to 4 instances"
echo "=========================================="

# First, build the JAR
echo "Building server-v2..."
cd server-v2
mvn clean package -DskipTests
cd ..

if [ ! -f "server-v2/target/server-v2-2.0.0.jar" ]; then
  echo "❌ JAR file not found! Build failed."
  exit 1
fi

echo "✓ JAR built successfully"
echo ""

# Deploy to each server
for i in "${!SERVER_IPS[@]}"; do
  SERVER_NUM=$((i+1))
  IP="${SERVER_IPS[$i]}"

  echo "=========================================="
  echo "Deploying to Server $SERVER_NUM: $IP"
  echo "=========================================="

  # Create application.properties for this server
  cat > /tmp/server-$SERVER_NUM-application.properties << EOF
server.port=8080
server.id=server-$SERVER_NUM

aws.region=$REGION

sqs.queue.url.prefix=https://sqs.$REGION.amazonaws.com/$ACCOUNT_ID/chatflow-

management.endpoints.web.exposure.include=health,metrics,info
management.endpoint.health.show-details=always

logging.level.root=INFO
logging.level.com.chatflow=DEBUG
logging.pattern.console=%d{yyyy-MM-dd HH:mm:ss} - %msg%n
EOF

  echo "1. Uploading JAR..."
  scp -i $KEY_FILE -o StrictHostKeyChecking=no \
    server-v2/target/server-v2-2.0.0.jar ec2-user@$IP:~/

  echo "2. Uploading configuration..."
  scp -i $KEY_FILE -o StrictHostKeyChecking=no \
    /tmp/server-$SERVER_NUM-application.properties ec2-user@$IP:~/application.properties

  echo "3. Installing Java and starting server..."
  ssh -i $KEY_FILE -o StrictHostKeyChecking=no ec2-user@$IP << 'ENDSSH'
    # Install Java if not already installed
    if ! command -v java &> /dev/null; then
      echo "Installing Java 17..."
      sudo yum install java-17-amazon-corretto -y
    fi

    # Stop any existing server
    if [ -f server.pid ]; then
      echo "Stopping existing server..."
      kill $(cat server.pid) 2>/dev/null || true
      sleep 2
    fi

    # Start new server
    echo "Starting server..."
    nohup java -jar server-v2-2.0.0.jar > server.log 2>&1 &
    echo $! > server.pid

    # Wait for startup
    echo "Waiting for server to start..."
    sleep 10

    # Check health
    echo "Checking server health..."
    curl -s http://localhost:8080/health || echo "Health check failed"
ENDSSH

  echo "✓ Server $SERVER_NUM deployed successfully"
  echo ""

  # Small delay between deployments
  sleep 2
done

echo "=========================================="
echo "All servers deployed!"
echo "=========================================="
echo ""
echo "Server URLs:"
for i in "${!SERVER_IPS[@]}"; do
  SERVER_NUM=$((i+1))
  IP="${SERVER_IPS[$i]}"
  echo "  Server $SERVER_NUM: http://$IP:8080/health"
done