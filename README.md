# CS6650 Assignment 2

Complete implementation of a scalable chat system using Spring Boot, AWS SQS, and Application Load Balancer.

## Project Structure

```
assignment2/
│
├── server-v2/                          # WebSocket Server with SQS
│   ├── src/main/java/com/chatflow/server/
│   │   ├── config/
│   │   │   └── WebSocketConfig.java
│   │   ├── controllers/
│   │   │   └── HealthController.java
│   │   ├── models/
│   │   │   ├── ChatMessage.java
│   │   │   └── QueueMessage.java
│   │   ├── service/
│   │   │   └── SQSService.java
│   │   ├── utils/
│   │   │   └── ValidationUtils.java
│   │   ├── websocket/
│   │   │   └── ChatWebSocketHandler.java
│   │   └── ServerApplication.java
│   ├── src/main/resources/
│   │   └── application.yml
│   ├── pom.xml
│   └── README.md
│
├── consumer/                           # SQS Consumer & Broadcaster
│   ├── src/main/java/com/chatflow/consumer/
│   │   ├── config/
│   │   │   └── WebSocketConfig.java
│   │   ├── models/
│   │   │   ├── QueueMessage.java
│   │   │   └── BroadcastMessage.java
│   │   ├── service/
│   │   │   ├── MessageConsumer.java
│   │   │   └── MessageBroadcaster.java
│   │   ├── websocket/
│   │   │   └── ConsumerWebSocketHandler.java
│   │   └── ConsumerApplication.java
│   ├── src/main/resources/
│   │   └── application.yml
│   ├── pom.xml
│   └── README.md
│
├── deployment/                         # Deployment Scripts
│   ├── setup-sqs-queues.sh
│   ├── setup-alb.sh
│
├── monitoring/                         # Monitoring Tools
│   ├── cloudwatch-dashboard.json
│
└── README.md                          # This file
```

## Quick Start

### Prerequisites

- Java 17+
- Maven 3.8+
- AWS Account with CLI configured
- Python 3.8+ (for testing)

### 1. Setup AWS SQS Queues

```bash
cd deployment
./setup-sqs-queues.sh
```

This creates 20 FIFO queues: `chatflow-room-1.fifo` through `chatflow-room-20.fifo`

### 2. Build Applications

```bash
# Build server
cd server-v2
mvn clean package

# Build consumer
cd ../consumer
mvn clean package
```

### 3. Deploy to AWS

- EC2 instance setup
- Load balancer configuration
- Application deployment

### 4. Run Locally (Development)

**Terminal 1 - Start Server:**

```bash
cd server-v2
export AWS_REGION=us-west-2
export SQS_QUEUE_URL_PREFIX=https://sqs.us-west-2.amazonaws.com/YOUR_ACCOUNT_ID/chatflow-
mvn spring-boot:run
```

**Terminal 2 - Start Consumer:**

```bash
cd consumer
export AWS_REGION=us-west-2
export SQS_QUEUE_URL_PREFIX=https://sqs.us-west-2.amazonaws.com/YOUR_ACCOUNT_ID/chatflow-
mvn spring-boot:run
```

## Architecture Overview

### System Components

1. **WebSocket Servers (4x)**: Handle client connections, validate messages, publish to SQS
2. **Application Load Balancer**: Distributes connections with sticky sessions
3. **AWS SQS (20 FIFO Queues)**: Message queue for each chat room
4. **Consumer Application**: Polls queues, broadcasts messages to clients
5. **Monitoring**: CloudWatch metrics and custom dashboards

### Message Flow

```
Client → ALB → Server → SQS Queue → Consumer → Broadcast to Room
```

**SQS Batch Size:**

```java
// In MessageConsumer.java
MAX_MESSAGES_PER_POLL = 10  # Try: 5, 10, 20
```

### Load Balancer Tuning

**Idle Timeout:**

```bash
aws elbv2 modify-load-balancer-attributes \
  --load-balancer-arn <arn> \
  --attributes Key=idle_timeout.timeout_seconds,Value=120
```

## API Reference

### WebSocket Endpoint

**Connect:** `ws://<alb-dns>/chat/{roomId}`

**Send Message:**

```json
{
  "userId": "123",
  "username": "alice",
  "message": "Hello, world!",
  "timestamp": "2025-10-26T12:00:00Z",
  "messageType": "TEXT"
}
```

**Receive Message:**

```json
{
  "userId": "123",
  "username": "alice",
  "message": "Hello, world!",
  "timestamp": "2025-10-26T12:00:00Z",
  "messageType": "TEXT",
  "serverTimestamp": "2025-10-26T12:00:01Z",
  "status": "OK"
}
```

### Health Check Endpoint

**Server:** `GET http://<server-ip>:8080/health`

**Consumer:** `GET http://<consumer-ip>:8081/health`

**Response:**

```json
{
  "status": "UP",
  "timestamp": "2025-10-26T12:00:00Z"
}
```

## Monitoring

### CloudWatch Metrics

**SQS Metrics:**

- `NumberOfMessagesSent`
- `NumberOfMessagesReceived`
- `ApproximateNumberOfMessagesVisible`
- `ApproximateAgeOfOldestMessage`

**ALB Metrics:**

- `RequestCount`
- `TargetResponseTime`
- `HealthyHostCount`
- `UnHealthyHostCount`

**EC2 Metrics:**

- `CPUUtilization`
- `NetworkIn/NetworkOut`
- `DiskReadOps/DiskWriteOps`

### Custom Application Metrics

**Server (Micrometer):**

- `websocket.messages.received`
- `websocket.validation.errors`
- `sqs.messages.published`
- `sqs.publish.failures`

**Consumer (Micrometer):**

- `consumer.messages.consumed`
- `consumer.messages.failed`
- `consumer.processing.time`
- `broadcaster.messages.sent`

## Troubleshooting

### Common Issues

**1. Queue Depth Growing**

```bash
# Check consumer is running
systemctl status chatflow-consumer

# Check consumer logs
tail -f /var/log/chatflow-consumer.log

# Increase consumer threads
vim application.yml  # Increase consumer.thread.count
systemctl restart chatflow-consumer
```

**2. Uneven Load Distribution**

```bash
# Verify sticky sessions
aws elbv2 describe-target-group-attributes \
  --target-group-arn <arn> \
  | grep stickiness

# Check ALB target health
aws elbv2 describe-target-health \
  --target-group-arn <arn>
```

**3. Connection Timeouts**

```bash
# Increase ALB idle timeout
aws elbv2 modify-load-balancer-attributes \
  --load-balancer-arn <arn> \
  --attributes Key=idle_timeout.timeout_seconds,Value=300
```

**4. SQS Permission Denied**

```bash
# Verify IAM role
aws iam get-role --role-name ChatFlowEC2Role

# Test SQS access
aws sqs send-message \
  --queue-url <queue-url> \
  --message-body "test"
```

## Documentation

- [Architecture Documentation](docs/ARCHITECTURE.md)
- [Deployment Guide](docs/DEPLOYMENT-GUIDE.md)
- [Performance Tuning Guide](docs/PERFORMANCE-TUNING.md)
- [Assignment Requirements](assignment2.md)

## Development

### Build Commands

```bash
# Build server
cd server-v2
mvn clean package

# Build consumer
cd consumer
mvn clean package

# Run tests
mvn test

# Generate JAR with dependencies
mvn clean package -DskipTests
```
