package com.chatflow.serverv2.utils;

import com.chatflow.serverv2.models.ChatMessage;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import org.apache.commons.lang3.StringUtils;


/**
 * Validation logic for ChatMessage payloads.
 * Returns null if OK, otherwise an error message string describing the problem.
 */
public class ValidationUtils {

  public static String validate(ChatMessage m) {
    if (m == null) return "payload missing";

    // userId: accept numeric string or numeric JSON value mapped to String by Jackson
    if (StringUtils.isBlank(m.getUserId())) return "userId missing";
    int userIdInt;
    try {
      // strip quotes/whitespace, in case client sends e.g. " 123 "
      String uid = m.getUserId().trim();
      userIdInt = Integer.parseInt(uid);
    } catch (NumberFormatException e) {
      return "userId must be an integer";
    }
    if (userIdInt < 1 || userIdInt > 100000) return "userId out of range (1-100000)";

    // username
    if (StringUtils.isBlank(m.getUsername())) return "username missing";
    if (!m.getUsername().matches("^[A-Za-z0-9]{3,20}$"))
      return "username invalid (3-20 alphanumeric)";

    // message
    if (StringUtils.isBlank(m.getMessage())) return "message missing";
    int len = m.getMessage().length();
    if (len < 1 || len > 500) return "message length invalid (1-500 chars)";

    // timestamp - ISO-8601
    if (StringUtils.isBlank(m.getTimestamp())) return "timestamp missing";
    try {
      OffsetDateTime.parse(m.getTimestamp());
    } catch (DateTimeParseException e) {
      return "timestamp not valid ISO-8601";
    }

    // messageType
    if (StringUtils.isBlank(m.getMessageType())) return "messageType missing";
    String mt = m.getMessageType();
    if (!(mt.equals("TEXT") || mt.equals("JOIN") || mt.equals("LEAVE")))
      return "messageType invalid (must be TEXT|JOIN|LEAVE)";

    return null; // OK
  }
}