package com.chatflow.serverv2.websocket;

import com.chatflow.serverv2.models.ChatMessage;
import com.chatflow.serverv2.models.QueueMessage;
import com.chatflow.serverv2.service.SQSService;
import com.chatflow.serverv2.utils.ValidationUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler that publishes messages to SQS instead of echoing.
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

  private static final Logger logger = LoggerFactory.getLogger(ChatWebSocketHandler.class);

  private final ObjectMapper mapper = new ObjectMapper();
  private final SQSService sqsService;

  @Value("${server.id:unknown}")
  private String serverId;

  // Track active sessions per room
  private final Map<String, Map<String, WebSocketSession>> rooms = new ConcurrentHashMap<>();

  // Metrics
  private final Counter messagesReceived;
  private final Counter validationErrors;
  private final Counter queuePublishErrors;

  @Autowired
  public ChatWebSocketHandler(SQSService sqsService, MeterRegistry meterRegistry) {
    this.sqsService = sqsService;
    this.messagesReceived = Counter.builder("websocket.messages.received")
        .description("Total messages received from clients")
        .register(meterRegistry);
    this.validationErrors = Counter.builder("websocket.validation.errors")
        .description("Messages that failed validation")
        .register(meterRegistry);
    this.queuePublishErrors = Counter.builder("websocket.queue.publish.errors")
        .description("Messages that failed to publish to queue")
        .register(meterRegistry);
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    String roomId = extractRoomIdFromSession(session);
    rooms.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>())
        .put(session.getId(), session);

    logger.info("Session {} connected to room {} | Total rooms: {}",
        session.getId(), roomId, rooms.size());
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    String payload = message.getPayload();
    messagesReceived.increment();

    ChatMessage msg;

    // Parse JSON
    try {
      msg = mapper.readValue(payload, ChatMessage.class);
    } catch (Exception e) {
      validationErrors.increment();
      sendError(session, "INVALID_JSON", "Malformed JSON payload");
      logger.warn("Invalid JSON from session {}: {}", session.getId(), payload);
      return;
    }

    // Validate
    String validationError = ValidationUtils.validate(msg);
    if (validationError != null) {
      validationErrors.increment();
      sendError(session, "VALIDATION_ERROR", validationError);
      logger.warn("Validation failed for session {}: {}", session.getId(), validationError);
      return;
    }

    // Extract metadata
    String roomId = extractRoomIdFromSession(session);
    String clientIp = extractClientIp(session);

    // Create queue message
    QueueMessage queueMessage = QueueMessage.fromChatMessage(msg, roomId, serverId, clientIp);

    // Publish to SQS
    boolean published = sqsService.publishMessage(queueMessage);

    if (published) {
      // Send acknowledgment to sender
      sendAcknowledgment(session, msg);
      logger.debug("Message from user {} published to room {}",
          msg.getUserId(), roomId);
    } else {
      queuePublishErrors.increment();
      sendError(session, "QUEUE_ERROR", "Failed to publish message to queue");
      logger.error("Failed to publish message from session {} to queue", session.getId());
    }
  }

  /**
   * Sends an acknowledgment response to the client
   */
  private void sendAcknowledgment(WebSocketSession session, ChatMessage originalMsg) {
    try {
      ChatMessage ack = new ChatMessage();
      ack.setUserId(originalMsg.getUserId());
      ack.setUsername(originalMsg.getUsername());
      ack.setMessage(originalMsg.getMessage());
      ack.setTimestamp(originalMsg.getTimestamp());
      ack.setMessageType(originalMsg.getMessageType());
      ack.setServerTimestamp(Instant.now().toString());
      ack.setStatus("OK");

      String response = mapper.writeValueAsString(ack);
      session.sendMessage(new TextMessage(response));
    } catch (Exception e) {
      logger.error("Failed to send acknowledgment to session {}: {}",
          session.getId(), e.getMessage());
    }
  }

  /**
   * Sends an error message to the client
   */
  private void sendError(WebSocketSession session, String code, String message) {
    try {
      ChatMessage err = new ChatMessage();
      err.setStatus("ERROR");
      err.setErrorCode(code);
      err.setErrorMessage(message);
      err.setServerTimestamp(Instant.now().toString());

      String errorJson = mapper.writeValueAsString(err);
      session.sendMessage(new TextMessage(errorJson));
    } catch (Exception ex) {
      logger.error("Failed to send error response to session {}: {}",
          session.getId(), ex.getMessage());
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    String roomId = extractRoomIdFromSession(session);
    Map<String, WebSocketSession> roomSessions = rooms.get(roomId);

    if (roomSessions != null) {
      roomSessions.remove(session.getId());
      if (roomSessions.isEmpty()) {
        rooms.remove(roomId);
      }
    }

    logger.info("Session {} disconnected from room {} (code: {}, reason: {}) | Total rooms: {}",
        session.getId(), roomId, status.getCode(), status.getReason(), rooms.size());
  }

  /**
   * Extracts room ID from WebSocket session URI
   */
  private String extractRoomIdFromSession(WebSocketSession session) {
    if (session == null || session.getUri() == null) {
      return "unknown";
    }
    String path = session.getUri().getPath();
    if (path == null || path.isEmpty()) {
      return "unknown";
    }
    String[] parts = path.split("/");
    return parts.length > 0 ? parts[parts.length - 1] : "unknown";
  }

  /**
   * Extracts client IP from WebSocket session
   */
  private String extractClientIp(WebSocketSession session) {
    try {
      InetSocketAddress remoteAddress = session.getRemoteAddress();
      if (remoteAddress != null) {
        return remoteAddress.getAddress().getHostAddress();
      }
    } catch (Exception e) {
      logger.debug("Failed to extract client IP: {}", e.getMessage());
    }
    return "unknown";
  }

  /**
   * Gets current connection statistics
   */
  public String getConnectionStats() {
    int totalSessions = rooms.values().stream()
        .mapToInt(Map::size)
        .sum();
    return String.format("Active rooms: %d, Total sessions: %d",
        rooms.size(), totalSessions);
  }
}