package com.chatflow.serverv2.models;

/**
 * Model for incoming and outgoing chat messages.
 * Note: userId is stored as String to accept both numeric and quoted numeric JSON values.
 */
public class ChatMessage {
  private String userId;
  private String username;
  private String message;
  private String timestamp; // client timestamp, ISO-8601
  private String messageType; // TEXT|JOIN|LEAVE

  // server fields
  private String serverTimestamp;
  private String status; // OK or ERROR
  private String errorCode;
  private String errorMessage;

  // getters and setters
  public String getUserId() { return userId; }
  public void setUserId(String userId) { this.userId = userId; }

  public String getUsername() { return username; }
  public void setUsername(String username) { this.username = username; }

  public String getMessage() { return message; }
  public void setMessage(String message) { this.message = message; }

  public String getTimestamp() { return timestamp; }
  public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

  public String getMessageType() { return messageType; }
  public void setMessageType(String messageType) { this.messageType = messageType; }

  public String getServerTimestamp() { return serverTimestamp; }
  public void setServerTimestamp(String serverTimestamp) { this.serverTimestamp = serverTimestamp; }

  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }

  public String getErrorCode() { return errorCode; }
  public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

  public String getErrorMessage() { return errorMessage; }
  public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}