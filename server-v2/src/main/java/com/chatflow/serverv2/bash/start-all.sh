#!/bin/bash

KEY_FILE="6650-assignment1-key.pem"

# Server IPs
declare -a SERVER_IPS=(
  "34.210.165.37"
  "18.236.151.153"
  "35.87.73.243"
  "54.245.151.129"
)

# Consumer is on first server
CONSUMER_IP="34.210.165.37"

echo "=========================================="
echo "Starting All Services"
echo "=========================================="

# Step 1: Start all servers
echo ""
echo "Starting servers..."
echo "===================="

for i in "${!SERVER_IPS[@]}"; do
  SERVER_NUM=$((i+1))
  IP="${SERVER_IPS[$i]}"

  echo -n "Server $SERVER_NUM ($IP): "

  ssh -i $KEY_FILE -o StrictHostKeyChecking=no ec2-user@$IP << ENDSSH > /dev/null 2>&1
    # Stop existing server
    if [ -f server.pid ]; then
      kill \$(cat server.pid) 2>/dev/null || true
      sleep 1
    fi

    # Start server
    SERVER_ID=server-$SERVER_NUM nohup java -jar server-v2-2.0.0.jar > server.log 2>&1 &
    echo \$! > server.pid
ENDSSH

  echo "✓ Started"
done

# Step 2: Start consumer
echo ""
echo "Starting consumer..."
echo "===================="

echo -n "Consumer ($CONSUMER_IP): "

ssh -i $KEY_FILE -o StrictHostKeyChecking=no ec2-user@$CONSUMER_IP << 'ENDSSH' > /dev/null 2>&1
  # Stop existing consumer
  if [ -f consumer.pid ]; then
    kill $(cat consumer.pid) 2>/dev/null || true
    sleep 1
  fi

  # Start consumer
  nohup java -Xmx6g -jar consumer-0.0.1-SNAPSHOT.jar > consumer.log 2>&1 &
  echo $! > consumer.pid
ENDSSH

echo "✓ Started"

# Step 3: Wait and verify
echo ""
echo "Waiting for services to start..."
sleep 15

echo ""
echo "Verifying services..."
echo "===================="

# Check servers
echo ""
echo "Servers:"
for i in "${!SERVER_IPS[@]}"; do
  SERVER_NUM=$((i+1))
  IP="${SERVER_IPS[$i]}"

  echo -n "  Server $SERVER_NUM ($IP): "

  RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" http://$IP:8080/health --connect-timeout 5)

  if [ "$RESPONSE" = "200" ]; then
    echo "✓ UP"
  else
    echo "✗ DOWN"
  fi
done

# Check consumer
echo ""
echo "Consumer:"
echo -n "  Consumer ($CONSUMER_IP): "

RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" http://$CONSUMER_IP:8081/health --connect-timeout 5)

if [ "$RESPONSE" = "200" ]; then
  echo "✓ UP"
else
  echo "✗ DOWN"
fi

echo ""
echo "=========================================="
echo "All services started!"
echo "=========================================="