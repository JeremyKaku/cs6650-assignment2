package com.chatflow.consumer.service;

import com.chatflow.consumer.models.BroadcastMessage;
import com.chatflow.consumer.models.QueueMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Broadcasts messages to WebSocket clients in specific rooms.
 * Maintains room membership and handles message delivery.
 */
@Service
public class MessageBroadcaster {

  private static final Logger logger = LoggerFactory.getLogger(MessageBroadcaster.class);

  private final ObjectMapper objectMapper = new ObjectMapper();

  // roomId -> Set<WebSocketSession>
  private final Map<String, Set<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();

  // sessionId -> roomId (for quick lookup)
  private final Map<String, String> sessionToRoom = new ConcurrentHashMap<>();

  // Simple counters instead of Micrometer metrics
  private final AtomicLong messagesBroadcasted = new AtomicLong(0);
  private final AtomicLong broadcastFailures = new AtomicLong(0);

  // Constructor - no parameters needed
  public MessageBroadcaster() {
    // Initialization if needed
  }

  /**
   * Adds a session to a room
   */
  public void addSessionToRoom(String roomId, WebSocketSession session) {
    roomSessions.computeIfAbsent(roomId, k -> new CopyOnWriteArraySet<>())
        .add(session);
    sessionToRoom.put(session.getId(), roomId);

    logger.info("Session {} joined room {}. Room size: {}",
        session.getId(), roomId, roomSessions.get(roomId).size());
  }

  /**
   * Removes a session from its room
   */
  public void removeSession(WebSocketSession session) {
    String roomId = sessionToRoom.remove(session.getId());

    if (roomId != null) {
      Set<WebSocketSession> sessions = roomSessions.get(roomId);
      if (sessions != null) {
        sessions.remove(session);

        if (sessions.isEmpty()) {
          roomSessions.remove(roomId);
        }

        logger.info("Session {} left room {}. Room size: {}",
            session.getId(), roomId, sessions.size());
      }
    }
  }

  /**
   * Broadcasts a message to all clients in a room
   */
  public boolean broadcastToRoom(String roomId, QueueMessage queueMessage) {
    Set<WebSocketSession> sessions = roomSessions.get(roomId);

    if (sessions == null || sessions.isEmpty()) {
      logger.debug("No active sessions in room {}", roomId);
      return true; // Not an error, just no one to send to
    }

    // Convert to broadcast message
    BroadcastMessage broadcastMsg = BroadcastMessage.fromQueueMessage(queueMessage);

    try {
      String messageJson = objectMapper.writeValueAsString(broadcastMsg);
      TextMessage textMessage = new TextMessage(messageJson);

      int successCount = 0;
      int failureCount = 0;

      // Send to all sessions in room
      for (WebSocketSession session : sessions) {
        try {
          if (session.isOpen()) {
            session.sendMessage(textMessage);
            successCount++;
            messagesBroadcasted.incrementAndGet();
          } else {
            // Session closed, remove it
            logger.debug("Removing closed session {} from room {}",
                session.getId(), roomId);
            sessions.remove(session);
            sessionToRoom.remove(session.getId());
          }
        } catch (Exception e) {
          failureCount++;
          broadcastFailures.incrementAndGet();
          logger.error("Failed to send message to session {} in room {}: {}",
              session.getId(), roomId, e.getMessage());

          // Remove problematic session
          sessions.remove(session);
          sessionToRoom.remove(session.getId());
        }
      }

      logger.debug("Broadcasted message to room {}. Success: {}, Failed: {}",
          roomId, successCount, failureCount);

      return successCount > 0 || failureCount == 0;

    } catch (Exception e) {
      logger.error("Error broadcasting to room {}: {}", roomId, e.getMessage());
      return false;
    }
  }

  /**
   * Gets current room statistics
   */
  public String getRoomStats() {
    int totalSessions = roomSessions.values().stream()
        .mapToInt(Set::size)
        .sum();
    return String.format("Active rooms: %d, Total sessions: %d",
        roomSessions.size(), totalSessions);
  }

  /**
   * Gets session count for a specific room
   */
  public int getRoomSessionCount(String roomId) {
    Set<WebSocketSession> sessions = roomSessions.get(roomId);
    return sessions != null ? sessions.size() : 0;
  }
}