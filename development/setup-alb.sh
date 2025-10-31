#!/bin/bash

REGION="us-west-2"

# Get VPC ID (from one of your instances)
VPC_ID=$(aws ec2 describe-instances \
  --filters "Name=ip-address,Values=54.190.193.216" \
  --query 'Reservations[0].Instances[0].VpcId' \
  --output text \
  --region $REGION)

echo "VPC ID: $VPC_ID"

# Get subnet IDs (need at least 2 for ALB)
SUBNET_IDS=$(aws ec2 describe-subnets \
  --filters "Name=vpc-id,Values=$VPC_ID" \
  --query 'Subnets[0:2].SubnetId' \
  --output text \
  --region $REGION)

SUBNET_1=$(echo $SUBNET_IDS | awk '{print $1}')
SUBNET_2=$(echo $SUBNET_IDS | awk '{print $2}')

echo "Subnets: $SUBNET_1, $SUBNET_2"

# Create security group for ALB
echo "Creating ALB security group..."
ALB_SG_ID=$(aws ec2 create-security-group \
  --group-name chatflow-alb-sg \
  --description "Security group for ChatFlow ALB" \
  --vpc-id $VPC_ID \
  --region $REGION \
  --query 'GroupId' \
  --output text)

echo "ALB Security Group: $ALB_SG_ID"

# Allow HTTP traffic to ALB
aws ec2 authorize-security-group-ingress \
  --group-id $ALB_SG_ID \
  --protocol tcp \
  --port 80 \
  --cidr 0.0.0.0/0 \
  --region $REGION

echo "Creating target group..."
TARGET_GROUP_ARN=$(aws elbv2 create-target-group \
  --name chatflow-websocket-targets \
  --protocol HTTP \
  --port 8080 \
  --vpc-id $VPC_ID \
  --health-check-path /health \
  --health-check-interval-seconds 30 \
  --health-check-timeout-seconds 5 \
  --healthy-threshold-count 2 \
  --unhealthy-threshold-count 3 \
  --region $REGION \
  --query 'TargetGroups[0].TargetGroupArn' \
  --output text)

echo "Target Group ARN: $TARGET_GROUP_ARN"

# Enable sticky sessions
echo "Enabling sticky sessions..."
aws elbv2 modify-target-group-attributes \
  --target-group-arn $TARGET_GROUP_ARN \
  --attributes \
    Key=stickiness.enabled,Value=true \
    Key=stickiness.type,Value=lb_cookie \
    Key=stickiness.lb_cookie.duration_seconds,Value=3600 \
  --region $REGION

# Get instance IDs
echo "Getting instance IDs..."
INSTANCE_IDS=$(aws ec2 describe-instances \
  --filters "Name=ip-address,Values=54.190.193.216,34.222.153.85,34.222.6.165,35.91.154.66" \
  --query 'Reservations[].Instances[].InstanceId' \
  --output text \
  --region $REGION)

echo "Instance IDs: $INSTANCE_IDS"

# Register targets
echo "Registering instances to target group..."
TARGETS=$(echo $INSTANCE_IDS | xargs -n1 | sed 's/^/Id=/' | xargs)
aws elbv2 register-targets \
  --target-group-arn $TARGET_GROUP_ARN \
  --targets $TARGETS \
  --region $REGION

# Create load balancer
echo "Creating Application Load Balancer..."
ALB_ARN=$(aws elbv2 create-load-balancer \
  --name chatflow-alb \
  --subnets $SUBNET_1 $SUBNET_2 \
  --security-groups $ALB_SG_ID \
  --scheme internet-facing \
  --type application \
  --region $REGION \
  --query 'LoadBalancers[0].LoadBalancerArn' \
  --output text)

echo "ALB ARN: $ALB_ARN"

# Wait for ALB to be active
echo "Waiting for ALB to become active..."
aws elbv2 wait load-balancer-available \
  --load-balancer-arns $ALB_ARN \
  --region $REGION

# Create listener
echo "Creating HTTP listener..."
aws elbv2 create-listener \
  --load-balancer-arn $ALB_ARN \
  --protocol HTTP \
  --port 80 \
  --default-actions Type=forward,TargetGroupArn=$TARGET_GROUP_ARN \
  --region $REGION

# Modify ALB attributes
echo "Configuring ALB attributes..."
aws elbv2 modify-load-balancer-attributes \
  --load-balancer-arn $ALB_ARN \
  --attributes Key=idle_timeout.timeout_seconds,Value=120 \
  --region $REGION

# Get ALB DNS name
ALB_DNS=$(aws elbv2 describe-load-balancers \
  --load-balancer-arns $ALB_ARN \
  --region $REGION \
  --query 'LoadBalancers[0].DNSName' \
  --output text)

echo ""
echo "=========================================="
echo "ALB Setup Complete!"
echo "=========================================="
echo ""
echo "ALB DNS: $ALB_DNS"
echo "WebSocket URL: ws://$ALB_DNS"
echo ""
echo "Testing (wait 30 seconds for health checks):"
echo "  curl http://$ALB_DNS/health"
echo ""
echo "Save this information:"
echo "  ALB ARN: $ALB_ARN"
echo "  Target Group ARN: $TARGET_GROUP_ARN"
echo "  ALB Security Group: $ALB_SG_ID"