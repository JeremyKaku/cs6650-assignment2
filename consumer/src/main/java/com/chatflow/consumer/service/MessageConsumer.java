package com.chatflow.consumer.service;

import com.chatflow.consumer.models.QueueMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Multi-threaded consumer that pulls messages from SQS queues
 * and routes them to the broadcaster.
 */
@Service
public class MessageConsumer {

  private static final Logger logger = LoggerFactory.getLogger(MessageConsumer.class);
  private static final int MAX_MESSAGES_PER_POLL = 10;
  private static final int WAIT_TIME_SECONDS = 20; // Long polling

  @Value("${consumer.thread.count:20}")
  private int consumerThreadCount;

  @Value("${aws.region:us-west-2}")
  private String awsRegion;

  @Value("${sqs.queue.url.prefix}")
  private String queueUrlPrefix;

  @Value("${sqs.rooms.start:1}")
  private int roomsStart;

  @Value("${sqs.rooms.end:20}")
  private int roomsEnd;

  private final MessageBroadcaster broadcaster;
  private final ObjectMapper objectMapper = new ObjectMapper();

  private SqsClient sqsClient;
  private ExecutorService executorService;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicLong messagesProcessed = new AtomicLong(0);

  // Simple counters instead of Micrometer metrics
  private final AtomicLong messagesConsumed = new AtomicLong(0);
  private final AtomicLong messagesFailed = new AtomicLong(0);
  private long totalProcessingTimeNanos = 0;

  @Autowired
  public MessageConsumer(MessageBroadcaster broadcaster) {
    this.broadcaster = broadcaster;
  }

  @PostConstruct
  public void start() {
    logger.info("Starting Message Consumer with {} threads", consumerThreadCount);

    // Initialize SQS client
    sqsClient = SqsClient.builder()
        .region(Region.of(awsRegion))
        .credentialsProvider(DefaultCredentialsProvider.create())
        .build();

    // Create thread pool
    executorService = Executors.newFixedThreadPool(consumerThreadCount,
        new ThreadFactory() {
          private int counter = 0;
          @Override
          public Thread newThread(Runnable r) {
            return new Thread(r, "consumer-thread-" + (++counter));
          }
        });

    running.set(true);

    // Distribute rooms across consumer threads
    List<String> queueUrls = generateQueueUrls();
    int queuesPerThread = (int) Math.ceil((double) queueUrls.size() / consumerThreadCount);

    for (int i = 0; i < consumerThreadCount; i++) {
      int start = i * queuesPerThread;
      int end = Math.min(start + queuesPerThread, queueUrls.size());

      if (start < queueUrls.size()) {
        List<String> assignedQueues = queueUrls.subList(start, end);
        executorService.submit(new ConsumerTask(assignedQueues));
        logger.info("Thread {} assigned queues: {}", i, assignedQueues);
      }
    }

    logger.info("Message Consumer started successfully");
  }

  @PreDestroy
  public void stop() {
    logger.info("Stopping Message Consumer");
    running.set(false);

    if (executorService != null) {
      executorService.shutdown();
      try {
        if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
          executorService.shutdownNow();
        }
      } catch (InterruptedException e) {
        executorService.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }

    if (sqsClient != null) {
      sqsClient.close();
    }

    logger.info("Message Consumer stopped. Total messages processed: {}",
        messagesProcessed.get());
  }

  /**
   * Generates queue URLs for all rooms
   */
  private List<String> generateQueueUrls() {
    List<String> urls = new CopyOnWriteArrayList<>();
    for (int i = roomsStart; i <= roomsEnd; i++) {
      urls.add(queueUrlPrefix + "room-" + i + ".fifo");
    }
    return urls;
  }

  /**
   * Consumer task that polls specific queues
   */
  private class ConsumerTask implements Runnable {
    private final List<String> queueUrls;

    public ConsumerTask(List<String> queueUrls) {
      this.queueUrls = queueUrls;
    }

    @Override
    public void run() {
      logger.info("Consumer task started for queues: {}", queueUrls);

      while (running.get()) {
        for (String queueUrl : queueUrls) {
          try {
            pollAndProcess(queueUrl);
          } catch (Exception e) {
            logger.error("Error polling queue {}: {}", queueUrl, e.getMessage());
            // Continue to next queue
          }
        }
      }

      logger.info("Consumer task stopped for queues: {}", queueUrls);
    }

    /**
     * Polls a queue and processes messages
     */
    private void pollAndProcess(String queueUrl) {
      try {
        ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
            .queueUrl(queueUrl)
            .maxNumberOfMessages(MAX_MESSAGES_PER_POLL)
            .waitTimeSeconds(WAIT_TIME_SECONDS)
            .build();

        ReceiveMessageResponse response = sqsClient.receiveMessage(receiveRequest);
        List<Message> messages = response.messages();

        if (messages.isEmpty()) {
          return;
        }

        logger.debug("Received {} messages from queue {}", messages.size(), queueUrl);

        for (Message message : messages) {
          processMessage(queueUrl, message);
        }

      } catch (QueueDoesNotExistException e) {
        logger.error("Queue does not exist: {}", queueUrl);
        running.set(false); // Stop if queue doesn't exist
      } catch (Exception e) {
        logger.error("Error receiving messages from {}: {}", queueUrl, e.getMessage());
      }
    }

    /**
     * Processes a single message
     */
    private void processMessage(String queueUrl, Message message) {
      long startTime = System.nanoTime();

      try {
        // Parse message body
        QueueMessage queueMessage = objectMapper.readValue(
            message.body(), QueueMessage.class);

        // Broadcast to room
        boolean broadcasted = broadcaster.broadcastToRoom(
            queueMessage.getRoomId(), queueMessage);

        if (broadcasted) {
          // Acknowledge message
          deleteMessage(queueUrl, message.receiptHandle());
          messagesConsumed.incrementAndGet();
          messagesProcessed.incrementAndGet();

          logger.debug("Processed message {} for room {}",
              queueMessage.getMessageId(), queueMessage.getRoomId());
        } else {
          // Broadcast failed, message will be retried
          messagesFailed.incrementAndGet();
          logger.warn("Failed to broadcast message {} to room {}",
              queueMessage.getMessageId(), queueMessage.getRoomId());
        }

      } catch (Exception e) {
        messagesFailed.incrementAndGet();
        logger.error("Error processing message from {}: {}", queueUrl, e.getMessage());
      } finally {
        long endTime = System.nanoTime();
        totalProcessingTimeNanos += (endTime - startTime);
      }
    }

    /**
     * Deletes a message from the queue
     */
    private void deleteMessage(String queueUrl, String receiptHandle) {
      try {
        DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
            .queueUrl(queueUrl)
            .receiptHandle(receiptHandle)
            .build();

        sqsClient.deleteMessage(deleteRequest);
      } catch (Exception e) {
        logger.error("Failed to delete message: {}", e.getMessage());
      }
    }
  }

  /**
   * Gets consumer statistics
   */
  public String getStats() {
    return String.format("Running: %s, Threads: %d, Messages processed: %d",
        running.get(), consumerThreadCount, messagesProcessed.get());
  }
}