#!/bin/bash

# AWS SQS Queue Setup Script for ChatFlow
# Creates 20 FIFO queues for rooms 1-20

set -e

# Configuration
REGION="${AWS_REGION:-us-west-2}"
QUEUE_PREFIX="chatflow-room-"
MESSAGE_RETENTION_PERIOD=3600  # 1 hour
VISIBILITY_TIMEOUT=30          # 30 seconds
RECEIVE_WAIT_TIME=20           # Long polling

echo "Setting up SQS queues in region: $REGION"
echo "Queue prefix: $QUEUE_PREFIX"

# Function to create a FIFO queue
create_fifo_queue() {
  local room_id=$1
  local queue_name="${QUEUE_PREFIX}${room_id}.fifo"

  echo "Creating queue: $queue_name"

  aws sqs create-queue \
    --region "$REGION" \
    --queue-name "$queue_name" \
    --attributes '{
      "FifoQueue": "true",
      "ContentBasedDeduplication": "false",
      "MessageRetentionPeriod": "'"$MESSAGE_RETENTION_PERIOD"'",
      "VisibilityTimeout": "'"$VISIBILITY_TIMEOUT"'",
      "ReceiveMessageWaitTimeSeconds": "'"$RECEIVE_WAIT_TIME"'"
    }' \
    --output text --query 'QueueUrl'
}

# Create queues for rooms 1-20
for i in {1..20}; do
  create_fifo_queue "$i"
  sleep 1  # Rate limiting
done

echo ""
echo "✓ Successfully created 20 FIFO queues"
echo ""
echo "Queue URL format:"
echo "https://sqs.$REGION.amazonaws.com/YOUR_ACCOUNT_ID/${QUEUE_PREFIX}X.fifo"
echo ""
echo "Next steps:"
echo "1. Get your AWS account ID: aws sts get-caller-identity --query Account --output text"
echo "2. Update application.yml with the queue URL prefix"
echo "3. Ensure IAM role has SQS permissions"