package com.chatflow.consumer.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Message format sent to WebSocket clients.
 */
public class BroadcastMessage {

  @JsonProperty("userId")
  private String userId;

  @JsonProperty("username")
  private String username;

  @JsonProperty("message")
  private String message;

  @JsonProperty("timestamp")
  private String timestamp;

  @JsonProperty("messageType")
  private String messageType;

  @JsonProperty("serverTimestamp")
  private String serverTimestamp;

  @JsonProperty("status")
  private String status;

  /**
   * Creates a BroadcastMessage from a QueueMessage
   */
  public static BroadcastMessage fromQueueMessage(QueueMessage qm) {
    BroadcastMessage bm = new BroadcastMessage();
    bm.setUserId(qm.getUserId());
    bm.setUsername(qm.getUsername());
    bm.setMessage(qm.getMessage());
    bm.setTimestamp(qm.getTimestamp());
    bm.setMessageType(qm.getMessageType());
    bm.setServerTimestamp(Instant.now().toString());
    bm.setStatus("OK");
    return bm;
  }

  // Getters and Setters
  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public String getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(String timestamp) {
    this.timestamp = timestamp;
  }

  public String getMessageType() {
    return messageType;
  }

  public void setMessageType(String messageType) {
    this.messageType = messageType;
  }

  public String getServerTimestamp() {
    return serverTimestamp;
  }

  public void setServerTimestamp(String serverTimestamp) {
    this.serverTimestamp = serverTimestamp;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }
}