package com.chatflow.serverv2.controllers;

import com.chatflow.serverv2.models.QueueMessage;
import com.chatflow.serverv2.websocket.ChatWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for Consumer to trigger message broadcasts to WebSocket clients.
 * Consumer calls this after reading from SQS and writing to DynamoDB.
 */
@RestController
@RequestMapping("/api")
public class BroadcastController {

  private static final Logger logger = LoggerFactory.getLogger(BroadcastController.class);

  @Autowired
  private ChatWebSocketHandler chatWebSocketHandler;

  /**
   * Endpoint for Consumer to request broadcast to a room.
   * POST /api/broadcast
   * Body: QueueMessage JSON
   */
  @PostMapping("/broadcast")
  public ResponseEntity<String> broadcastMessage(@RequestBody QueueMessage message) {
    try {
      logger.debug("Broadcast request received for room {} from consumer",
          message.getRoomId());

      int recipientCount = chatWebSocketHandler.broadcastToRoom(message);

      if (recipientCount > 0) {
        logger.debug("Successfully broadcasted message {} to {} clients in room {}",
            message.getMessageId(), recipientCount, message.getRoomId());
        return ResponseEntity.ok("Broadcasted to " + recipientCount + " clients");
      } else {
        logger.debug("No active clients in room {} to broadcast to",
            message.getRoomId());
        return ResponseEntity.ok("No active clients in room");
      }

    } catch (Exception e) {
      logger.error("Failed to broadcast message: {}", e.getMessage(), e);
      return ResponseEntity.status(500)
          .body("Broadcast failed: " + e.getMessage());
    }
  }

  /**
   * Health check endpoint for broadcast API
   */
  @GetMapping("/broadcast/health")
  public ResponseEntity<String> health() {
    return ResponseEntity.ok("Broadcast API is healthy");
  }
}