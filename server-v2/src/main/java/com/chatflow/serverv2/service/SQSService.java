package com.chatflow.serverv2.service;

import com.chatflow.serverv2.models.QueueMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service for publishing messages to AWS SQS.
 * Implements connection pooling and circuit breaker pattern.
 */
@Service
public class SQSService {

  private static final Logger logger = LoggerFactory.getLogger(SQSService.class);
  private static final int POOL_SIZE = 10;
  private static final int MAX_RETRIES = 3;
  private static final long CIRCUIT_BREAKER_TIMEOUT_MS = 30000;

  @Value("${aws.region:us-west-2}")
  private String awsRegion;

  @Value("${sqs.queue.url.prefix}")
  private String queueUrlPrefix;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final BlockingQueue<SqsClient> clientPool = new LinkedBlockingQueue<>();
  private final AtomicInteger failureCount = new AtomicInteger(0);
  private volatile long lastFailureTime = 0;
  private volatile boolean circuitOpen = false;

  private Counter messagesPublished;
  private Counter publishFailures;

  public SQSService(MeterRegistry meterRegistry) {
    this.messagesPublished = Counter.builder("sqs.messages.published")
        .description("Total messages published to SQS")
        .register(meterRegistry);
    this.publishFailures = Counter.builder("sqs.publish.failures")
        .description("Failed SQS publish attempts")
        .register(meterRegistry);
  }

  @PostConstruct
  public void init() {
    logger.info("Initializing SQS client pool with size: {}", POOL_SIZE);

    for (int i = 0; i < POOL_SIZE; i++) {
      try {
        SqsClient client = SqsClient.builder()
            .region(Region.of(awsRegion))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();
        clientPool.offer(client);
      } catch (Exception e) {
        logger.error("Failed to create SQS client {}: {}", i, e.getMessage());
      }
    }

    logger.info("SQS client pool initialized with {} clients", clientPool.size());
  }

  @PreDestroy
  public void cleanup() {
    logger.info("Shutting down SQS client pool");
    clientPool.forEach(SqsClient::close);
    clientPool.clear();
  }

  /**
   * Publishes a message to the appropriate room queue.
   *
   * @param message The queue message to publish
   * @return true if successful, false otherwise
   */
  public boolean publishMessage(QueueMessage message) {
    // Check circuit breaker
    if (isCircuitOpen()) {
      logger.warn("Circuit breaker is OPEN, rejecting message publish");
      return false;
    }

    SqsClient client = null;
    try {
      // Get client from pool
      client = clientPool.poll(5, TimeUnit.SECONDS);
      if (client == null) {
        logger.error("Failed to obtain SQS client from pool");
        publishFailures.increment();
        return false;
      }

      // Determine queue URL based on room ID
      String queueUrl = getQueueUrl(message.getRoomId());

      // Serialize message
      String messageBody = objectMapper.writeValueAsString(message);

      // Publish with retry logic
      boolean success = publishWithRetry(client, queueUrl, messageBody, message.getMessageId());

      if (success) {
        messagesPublished.increment();
        resetCircuitBreaker();
      } else {
        publishFailures.increment();
        recordFailure();
      }

      return success;

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logger.error("Interrupted while waiting for SQS client");
      publishFailures.increment();
      return false;
    } catch (Exception e) {
      logger.error("Error publishing message to SQS: {}", e.getMessage(), e);
      publishFailures.increment();
      recordFailure();
      return false;
    } finally {
      // Return client to pool
      if (client != null) {
        clientPool.offer(client);
      }
    }
  }

  /**
   * Publishes message with retry logic
   */
  private boolean publishWithRetry(SqsClient client, String queueUrl,
      String messageBody, String messageId) {
    for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
      try {
        SendMessageRequest request = SendMessageRequest.builder()
            .queueUrl(queueUrl)
            .messageBody(messageBody)
            .messageGroupId(messageId.substring(0, 8)) // For FIFO grouping
            .messageDeduplicationId(messageId)
            .build();

        SendMessageResponse response = client.sendMessage(request);

        logger.debug("Message published to queue {} with ID: {}",
            queueUrl, response.messageId());
        return true;

      } catch (QueueDoesNotExistException e) {
        logger.error("Queue does not exist: {}", queueUrl);
        return false; // Don't retry for non-existent queue
      } catch (Exception e) {
        logger.warn("Publish attempt {} failed: {}", attempt, e.getMessage());
        if (attempt == MAX_RETRIES) {
          logger.error("All retry attempts exhausted for queue: {}", queueUrl);
          return false;
        }
        // Exponential backoff
        try {
          Thread.sleep((long) Math.pow(2, attempt) * 100);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
    }
    return false;
  }

  /**
   * Gets the queue URL for a given room ID
   */
  private String getQueueUrl(String roomId) {
    return queueUrlPrefix + "room-" + roomId + ".fifo";
  }

  /**
   * Records a failure and checks if circuit breaker should open
   */
  private void recordFailure() {
    int failures = failureCount.incrementAndGet();
    lastFailureTime = System.currentTimeMillis();

    if (failures >= 10) {
      circuitOpen = true;
      logger.error("Circuit breaker OPENED after {} failures", failures);
    }
  }

  /**
   * Resets circuit breaker on successful operation
   */
  private void resetCircuitBreaker() {
    if (circuitOpen || failureCount.get() > 0) {
      circuitOpen = false;
      failureCount.set(0);
      logger.info("Circuit breaker CLOSED - service recovered");
    }
  }

  /**
   * Checks if circuit breaker is open
   */
  private boolean isCircuitOpen() {
    if (!circuitOpen) {
      return false;
    }

    // Check if timeout has passed
    long timeSinceFailure = System.currentTimeMillis() - lastFailureTime;
    if (timeSinceFailure > CIRCUIT_BREAKER_TIMEOUT_MS) {
      logger.info("Circuit breaker timeout passed, attempting to close");
      circuitOpen = false;
      failureCount.set(0);
      return false;
    }

    return true;
  }

  /**
   * Gets current pool statistics
   */
  public String getPoolStats() {
    return String.format("Pool: %d/%d available, Failures: %d, Circuit: %s",
        clientPool.size(), POOL_SIZE, failureCount.get(),
        circuitOpen ? "OPEN" : "CLOSED");
  }
}