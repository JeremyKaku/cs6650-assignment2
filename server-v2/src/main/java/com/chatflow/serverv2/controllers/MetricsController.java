package com.chatflow.serverv2.controllers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Metrics API for Assignment 3.
 * Provides endpoints to query DynamoDB for core queries and analytics.
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

  @Value("${dynamodb.table.messages:chatflow-messages}")
  private String messagesTable;

  @Value("${dynamodb.table.participation:chatflow-room-participation}")
  private String participationTable;

  @Value("${aws.region:us-west-2}")
  private String awsRegion;

  private DynamoDbClient dynamoDbClient;

  @PostConstruct
  public void init() {
    dynamoDbClient = DynamoDbClient.builder()
        .region(Region.of(awsRegion))
        .credentialsProvider(DefaultCredentialsProvider.create())
        .build();
  }

  /**
   * Core Query 1: Get messages for a room in time range
   * GET /api/metrics/room/{roomId}/messages?start=...&end=...
   */
  @GetMapping("/room/{roomId}/messages")
  public Map<String, Object> getRoomMessages(
      @PathVariable String roomId,
      @RequestParam String start,
      @RequestParam String end) {

    long startTime = System.currentTimeMillis();

    try {
      Map<String, AttributeValue> values = new HashMap<>();
      values.put(":roomId", AttributeValue.builder().s(roomId).build());
      values.put(":start", AttributeValue.builder().s(start).build());
      values.put(":end", AttributeValue.builder().s(end).build());

      QueryRequest request = QueryRequest.builder()
          .tableName(messagesTable)
          .keyConditionExpression("roomId = :roomId AND #ts BETWEEN :start AND :end")
          .expressionAttributeNames(Map.of("#ts", "timestamp"))
          .expressionAttributeValues(values)
          .build();

      QueryResponse response = dynamoDbClient.query(request);

      long queryTime = System.currentTimeMillis() - startTime;

      List<Map<String, String>> messages = response.items().stream()
          .map(this::attributeMapToMessage)
          .collect(Collectors.toList());


      Map<String, Object> result = new HashMap<>();
      result.put("roomId", roomId);
      result.put("startTime", start);
      result.put("endTime", end);
      result.put("messageCount", messages.size());
//      result.put("messages", messages);
      result.put("queryTimeMs", queryTime);
      result.put("performanceTarget", "< 100ms");
      result.put("performanceMet", queryTime < 100);

      return result;

    } catch (Exception e) {
      return Map.of("error", e.getMessage());
    }
  }

  /**
   * Core Query 2: Get user's message history
   * GET /api/metrics/user/{userId}/messages?start=...&end=...
   */
  @GetMapping("/user/{userId}/messages")
  public Map<String, Object> getUserMessages(
      @PathVariable String userId,
      @RequestParam(required = false) String start,
      @RequestParam(required = false) String end) {

    long startTime = System.currentTimeMillis();

    try {
      Map<String, AttributeValue> values = new HashMap<>();
      values.put(":userId", AttributeValue.builder().s(userId).build());

      String keyCondition = "userId = :userId";

      if (start != null && end != null) {
        values.put(":start", AttributeValue.builder().s(start).build());
        values.put(":end", AttributeValue.builder().s(end).build());
        keyCondition += " AND #ts BETWEEN :start AND :end";
      }

      QueryRequest request = QueryRequest.builder()
          .tableName(messagesTable)
          .indexName("UserMessagesIndex")
          .keyConditionExpression(keyCondition)
          .expressionAttributeNames(start != null ? Map.of("#ts", "timestamp") : Map.of())
          .expressionAttributeValues(values)
          .build();

      QueryResponse response = dynamoDbClient.query(request);

      long queryTime = System.currentTimeMillis() - startTime;

      List<Map<String, String>> messages = response.items().stream()
          .map(this::attributeMapToMessage)
          .collect(Collectors.toList());

      Map<String, Object> result = new HashMap<>();
      result.put("userId", userId);
      result.put("messageCount", messages.size());
      result.put("messages", messages);
      result.put("queryTimeMs", queryTime);
      result.put("performanceTarget", "< 200ms");
      result.put("performanceMet", queryTime < 200);

      return result;

    } catch (Exception e) {
      return Map.of("error", e.getMessage());
    }
  }

  /**
   * Core Query 3: Count active users in time window
   * GET /api/metrics/users/active?start=...&end=...
   */
  @GetMapping("/users/active")
  public Map<String, Object> getActiveUsers(
      @RequestParam String start,
      @RequestParam String end) {

    long startTime = System.currentTimeMillis();

    try {
      // Scan with filter (for small datasets this is OK)
      Map<String, AttributeValue> values = new HashMap<>();
      values.put(":start", AttributeValue.builder().s(start).build());
      values.put(":end", AttributeValue.builder().s(end).build());

      ScanRequest request = ScanRequest.builder()
          .tableName(messagesTable)
          .filterExpression("#ts BETWEEN :start AND :end")
          .expressionAttributeNames(Map.of("#ts", "timestamp"))
          .expressionAttributeValues(values)
          .projectionExpression("userId")
          .build();

      ScanResponse response = dynamoDbClient.scan(request);

      // Count unique users
      Set<String> uniqueUsers = response.items().stream()
          .map(item -> item.get("userId").s())
          .collect(Collectors.toSet());

      long queryTime = System.currentTimeMillis() - startTime;

      Map<String, Object> result = new HashMap<>();
      result.put("startTime", start);
      result.put("endTime", end);
      result.put("activeUserCount", uniqueUsers.size());
      result.put("queryTimeMs", queryTime);
      result.put("performanceTarget", "< 500ms");
      result.put("performanceMet", queryTime < 500);

      return result;

    } catch (Exception e) {
      return Map.of("error", e.getMessage());
    }
  }

  /**
   * Core Query 4: Get rooms user has participated in
   * GET /api/metrics/user/{userId}/rooms
   */
  @GetMapping("/user/{userId}/rooms")
  public Map<String, Object> getUserRooms(@PathVariable String userId) {

    long startTime = System.currentTimeMillis();

    try {
      Map<String, AttributeValue> values = new HashMap<>();
      values.put(":userId", AttributeValue.builder().s(userId).build());

      QueryRequest request = QueryRequest.builder()
          .tableName(participationTable)
          .keyConditionExpression("userId = :userId")
          .expressionAttributeValues(values)
          .build();

      QueryResponse response = dynamoDbClient.query(request);

      long queryTime = System.currentTimeMillis() - startTime;

      List<Map<String, Object>> rooms = response.items().stream()
          .map(item -> {
            Map<String, Object> room = new HashMap<>();
            room.put("roomId", item.get("roomId").s());
            room.put("messageCount", item.containsKey("messageCount") ?
                item.get("messageCount").n() : "0");
            room.put("lastActivityTime", item.containsKey("lastActivityTime") ?
                item.get("lastActivityTime").s() : "");
            return room;
          })
          .collect(Collectors.toList());

      Map<String, Object> result = new HashMap<>();
      result.put("userId", userId);
      result.put("roomCount", rooms.size());
      result.put("rooms", rooms);
      result.put("queryTimeMs", queryTime);
      result.put("performanceTarget", "< 50ms");
      result.put("performanceMet", queryTime < 50);

      return result;

    } catch (Exception e) {
      return Map.of("error", e.getMessage());
    }
  }

  /**
   * Analytics: Messages per minute statistics
   * GET /api/metrics/analytics/messages-per-minute?start=...&end=...
   */
  @GetMapping("/analytics/messages-per-minute")
  public Map<String, Object> getMessagesPerMinute(
      @RequestParam String start,
      @RequestParam String end) {

    // Scan all messages in time range and group by minute
    try {
      Map<String, AttributeValue> values = new HashMap<>();
      values.put(":start", AttributeValue.builder().s(start).build());
      values.put(":end", AttributeValue.builder().s(end).build());

      ScanRequest request = ScanRequest.builder()
          .tableName(messagesTable)
          .filterExpression("#ts BETWEEN :start AND :end")
          .expressionAttributeNames(Map.of("#ts", "timestamp"))
          .expressionAttributeValues(values)
          .projectionExpression("#ts")
          .build();

      ScanResponse response = dynamoDbClient.scan(request);

      // Group by minute
      Map<String, Long> minuteCounts = response.items().stream()
          .map(item -> item.get("timestamp").s().substring(0, 16)) // Truncate to minute
          .collect(Collectors.groupingBy(t -> t, Collectors.counting()));

      return Map.of(
          "startTime", start,
          "endTime", end,
          "messagesPerMinute", minuteCounts
      );

    } catch (Exception e) {
      return Map.of("error", e.getMessage());
    }
  }

  /**
   * Analytics: Most active users (top N)
   * GET /api/metrics/analytics/top-users?limit=10
   */
  @GetMapping("/analytics/top-users")
  public Map<String, Object> getTopUsers(@RequestParam(defaultValue = "10") int limit) {

    try {
      // Scan and count messages per user
      ScanRequest request = ScanRequest.builder()
          .tableName(messagesTable)
          .projectionExpression("userId, username")
          .build();

      ScanResponse response = dynamoDbClient.scan(request);

      // Count messages per user
      Map<String, Long> userCounts = response.items().stream()
          .collect(Collectors.groupingBy(
              item -> item.get("userId").s() + ":" + item.get("username").s(),
              Collectors.counting()
          ));

      // Sort and get top N
      List<Map<String, Object>> topUsers = userCounts.entrySet().stream()
          .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
          .limit(limit)
          .map(entry -> {
            String[] parts = entry.getKey().split(":");
            Map<String, Object> user = new HashMap<>();
            user.put("userId", parts[0]);
            user.put("username", parts.length > 1 ? parts[1] : "unknown");
            user.put("messageCount", entry.getValue());
            return user;
          })
          .collect(Collectors.toList());

      return Map.of(
          "topUsers", topUsers,
          "limit", limit
      );

    } catch (Exception e) {
      return Map.of("error", e.getMessage());
    }
  }

  /**
   * Analytics: Most active rooms (top N)
   * GET /api/metrics/analytics/top-rooms?limit=10
   */
  @GetMapping("/analytics/top-rooms")
  public Map<String, Object> getTopRooms(@RequestParam(defaultValue = "10") int limit) {

    try {
      ScanRequest request = ScanRequest.builder()
          .tableName(messagesTable)
          .projectionExpression("roomId")
          .build();

      ScanResponse response = dynamoDbClient.scan(request);

      Map<String, Long> roomCounts = response.items().stream()
          .collect(Collectors.groupingBy(
              item -> item.get("roomId").s(),
              Collectors.counting()
          ));

      List<Map<String, Object>> topRooms = roomCounts.entrySet().stream()
          .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
          .limit(limit)
          .map(entry -> {
            Map<String, Object> room = new HashMap<>();
            room.put("roomId", entry.getKey());
            room.put("messageCount", entry.getValue());
            return room;
          })
          .collect(Collectors.toList());

      return Map.of(
          "topRooms", topRooms,
          "limit", limit
      );

    } catch (Exception e) {
      return Map.of("error", e.getMessage());
    }
  }

  /**
   * Analytics: User participation patterns
   * GET /api/metrics/analytics/user-participation
   */
  @GetMapping("/analytics/user-participation")
  public Map<String, Object> getUserParticipationPatterns() {
    try {
      ScanRequest request = ScanRequest.builder()
          .tableName(participationTable)
          .build();

      ScanResponse response = dynamoDbClient.scan(request);

      // Count rooms per user
      Map<String, Integer> userRoomCounts = new HashMap<>();
      for (Map<String, AttributeValue> item : response.items()) {
        String userId = item.get("userId").s();
        userRoomCounts.merge(userId, 1, Integer::sum);
      }

      // Categorize users by participation level
      Map<String, Integer> distribution = new HashMap<>();
      distribution.put("singleRoom", 0);
      distribution.put("lightUsers", 0);      // 2-3 rooms
      distribution.put("moderateUsers", 0);   // 4-5 rooms
      distribution.put("heavyUsers", 0);      // 6+ rooms

      for (int roomCount : userRoomCounts.values()) {
        if (roomCount == 1) distribution.merge("singleRoom", 1, Integer::sum);
        else if (roomCount <= 3) distribution.merge("lightUsers", 1, Integer::sum);
        else if (roomCount <= 5) distribution.merge("moderateUsers", 1, Integer::sum);
        else distribution.merge("heavyUsers", 1, Integer::sum);
      }

      // Calculate average
      double avgRoomsPerUser = userRoomCounts.values().stream()
          .mapToInt(Integer::intValue)
          .average()
          .orElse(0.0);

      return Map.of(
          "totalUsers", userRoomCounts.size(),
          "averageRoomsPerUser", String.format("%.2f", avgRoomsPerUser),
          "distribution", distribution
      );

    } catch (Exception e) {
      return Map.of("error", e.getMessage());
    }
  }

  /**
   * Get all metrics in one call (for client to log after test)
   * GET /api/metrics/all
   */
  @GetMapping("/all")
  public Map<String, Object> getAllMetrics() {

    Map<String, Object> allMetrics = new HashMap<>();

    // Get time range for last hour
    String endTime = Instant.now().toString();
    String startTime = Instant.now().minus(1, ChronoUnit.HOURS).toString();

    // Core queries
    allMetrics.put("sampleRoomMessages", getRoomMessages("1", startTime, endTime));
    allMetrics.put("activeUsers", getActiveUsers(startTime, endTime));

    // Analytics
    allMetrics.put("topUsers", getTopUsers(10));
    allMetrics.put("topRooms", getTopRooms(10));
    allMetrics.put("messagesPerMinute", getMessagesPerMinute(startTime, endTime));

    // System stats
    allMetrics.put("timestamp", Instant.now().toString());
    allMetrics.put("queryNote", "All core queries and analytics executed");

    // User participation patterns
    allMetrics.put("userParticipation", getUserParticipationPatterns());

    return allMetrics;
  }

  /**
   * Helper: Convert DynamoDB item to message map
   */
  private Map<String, String> attributeMapToMessage(Map<String, AttributeValue> item) {
    Map<String, String> message = new HashMap<>();
    message.put("messageId", item.get("messageId").s());
    message.put("roomId", item.get("roomId").s());
    message.put("userId", item.get("userId").s());
    message.put("username", item.get("username").s());
    message.put("message", item.get("message").s());
    message.put("messageType", item.get("messageType").s());
    message.put("timestamp", item.get("timestamp").s());
    return message;
  }
}