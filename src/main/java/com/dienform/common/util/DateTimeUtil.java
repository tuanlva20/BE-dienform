package com.dienform.common.util;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import lombok.extern.slf4j.Slf4j;

/**
 * Utility class for handling timezone-aware datetime operations Ensures all datetime operations use
 * Vietnam timezone (Asia/Ho_Chi_Minh)
 */
@Slf4j
public class DateTimeUtil {

  private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
  private static final DateTimeFormatter FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  /**
   * Get current datetime in Vietnam timezone
   */
  public static LocalDateTime now() {
    return LocalDateTime.now(VIETNAM_ZONE);
  }

  /**
   * Get current datetime as ZonedDateTime in Vietnam timezone
   */
  public static ZonedDateTime nowZoned() {
    return ZonedDateTime.now(VIETNAM_ZONE);
  }

  /**
   * Convert LocalDateTime to Vietnam timezone
   */
  public static LocalDateTime toVietnamTime(LocalDateTime dateTime) {
    if (dateTime == null) {
      return null;
    }
    return dateTime.atZone(ZoneId.systemDefault()).withZoneSameInstant(VIETNAM_ZONE)
        .toLocalDateTime();
  }

  /**
   * Format datetime for logging
   */
  public static String formatForLog(LocalDateTime dateTime) {
    if (dateTime == null) {
      return "null";
    }
    return dateTime.format(FORMATTER) + " (VN)";
  }

  /**
   * Check if a datetime is in the future (Vietnam timezone)
   */
  public static boolean isInFuture(LocalDateTime dateTime) {
    if (dateTime == null) {
      return false;
    }
    return dateTime.isAfter(now());
  }

  /**
   * Check if a datetime is in the past (Vietnam timezone)
   */
  public static boolean isInPast(LocalDateTime dateTime) {
    if (dateTime == null) {
      return false;
    }
    return dateTime.isBefore(now());
  }

  /**
   * Get Vietnam timezone ID
   */
  public static ZoneId getVietnamZone() {
    return VIETNAM_ZONE;
  }

  /**
   * Parse ISO 8601 datetime string to LocalDateTime in Vietnam timezone Handles formats like:
   * "2025-08-24T17:00:00.000Z" (UTC) or "2025-08-25T00:00:00" (local time)
   */
  public static LocalDateTime parseIso8601ToVietnamTime(String isoString) {
    if (isoString == null || isoString.trim().isEmpty()) {
      return null;
    }

    try {
      // Handle ISO 8601 format with Z suffix (UTC)
      if (isoString.endsWith("Z")) {
        java.time.Instant instant = java.time.Instant.parse(isoString);
        LocalDateTime result = LocalDateTime.ofInstant(instant, VIETNAM_ZONE);
        log.debug("Parsed UTC date '{}' to Vietnam time: {}", isoString, result);
        return result;
      }

      // Handle ISO 8601 format without Z suffix - treat as local time in Vietnam timezone
      // This ensures consistency between startDate and endDate parsing
      LocalDateTime result = LocalDateTime.parse(isoString, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
      log.debug("Parsed local date '{}' as Vietnam time: {}", isoString, result);
      return result;

    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid ISO 8601 datetime format: " + isoString, e);
    }
  }

  /**
   * Convert LocalDateTime to ISO 8601 string in UTC
   */
  public static String toIso8601String(LocalDateTime dateTime) {
    if (dateTime == null) {
      return null;
    }

    java.time.Instant instant = dateTime.atZone(VIETNAM_ZONE).toInstant();
    return instant.toString();
  }
}
