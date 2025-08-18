package com.dienform.common.util;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import lombok.extern.slf4j.Slf4j;

/**
 * Utility class for consistent date/time handling across the application Provides timezone-aware
 * conversions and formatting
 */
@Slf4j
public class DateTimeUtil {

  private static final ZoneId VIETNAM_TIMEZONE = ZoneId.of("Asia/Ho_Chi_Minh");
  private static final ZoneId UTC_TIMEZONE = ZoneId.of("UTC");
  private static final DateTimeFormatter ISO_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

  /**
   * Convert LocalDateTime to Vietnam timezone
   * 
   * @param localDateTime the local date time to convert
   * @return LocalDateTime in Vietnam timezone
   */
  public static LocalDateTime toVietnamTime(LocalDateTime localDateTime) {
    if (localDateTime == null) {
      return null;
    }

    // Since FE sends data with @JsonFormat(timezone = "Asia/Ho_Chi_Minh"),
    // the LocalDateTime is already in Vietnam timezone, no conversion needed
    log.debug("Input already in Vietnam timezone: {}", localDateTime);
    return localDateTime;
  }

  /**
   * Convert LocalDateTime from UTC to Vietnam timezone
   * 
   * @param utcDateTime the UTC date time to convert
   * @return LocalDateTime in Vietnam timezone
   */
  public static LocalDateTime fromUtcToVietnam(LocalDateTime utcDateTime) {
    if (utcDateTime == null) {
      return null;
    }

    ZonedDateTime utcZoned = utcDateTime.atZone(UTC_TIMEZONE);
    ZonedDateTime vietnamZoned = utcZoned.withZoneSameInstant(VIETNAM_TIMEZONE);

    log.debug("Converting {} (UTC) to {} (Vietnam)", utcDateTime, vietnamZoned.toLocalDateTime());
    return vietnamZoned.toLocalDateTime();
  }

  /**
   * Convert LocalDateTime from Vietnam timezone to UTC
   * 
   * @param vietnamDateTime the Vietnam local date time to convert
   * @return LocalDateTime in UTC
   */
  public static LocalDateTime toUtcTime(LocalDateTime vietnamDateTime) {
    if (vietnamDateTime == null) {
      return null;
    }

    // Assume the input is in Vietnam time and convert to UTC
    ZonedDateTime vietnamZoned = vietnamDateTime.atZone(VIETNAM_TIMEZONE);
    ZonedDateTime utcZoned = vietnamZoned.withZoneSameInstant(UTC_TIMEZONE);

    log.debug("Converting {} (Vietnam) to {} (UTC)", vietnamDateTime, utcZoned.toLocalDateTime());
    return utcZoned.toLocalDateTime();
  }

  /**
   * Get current time in Vietnam timezone
   * 
   * @return current LocalDateTime in Vietnam timezone
   */
  public static LocalDateTime nowVietnam() {
    return LocalDateTime.now(VIETNAM_TIMEZONE);
  }

  /**
   * Get current time in UTC
   * 
   * @return current LocalDateTime in UTC
   */
  public static LocalDateTime nowUtc() {
    return LocalDateTime.now(UTC_TIMEZONE);
  }

  /**
   * Format LocalDateTime to ISO string
   * 
   * @param localDateTime the date time to format
   * @return formatted string
   */
  public static String formatIso(LocalDateTime localDateTime) {
    if (localDateTime == null) {
      return null;
    }
    return localDateTime.format(ISO_FORMATTER);
  }

  /**
   * Parse ISO string to LocalDateTime
   * 
   * @param isoString the ISO formatted string
   * @return parsed LocalDateTime
   */
  public static LocalDateTime parseIso(String isoString) {
    if (isoString == null || isoString.trim().isEmpty()) {
      return null;
    }
    return LocalDateTime.parse(isoString, ISO_FORMATTER);
  }

  /**
   * Validate timezone consistency for input date times
   * 
   * @param dateTime the date time to validate
   * @param fieldName the field name for logging
   * @return true if valid, false otherwise
   */
  public static boolean validateVietnamTimezone(LocalDateTime dateTime, String fieldName) {
    if (dateTime == null) {
      return true; // null is valid
    }

    try {
      LocalDateTime now = nowVietnam();

      // Check if the datetime is reasonable (not too far in past/future)
      if (dateTime.isAfter(now.plusYears(10))) {
        log.warn(
            "Timezone validation warning for {}: DateTime {} is too far in future, possible timezone issue",
            fieldName, dateTime);
        return false;
      }

      if (dateTime.isBefore(now.minusYears(10))) {
        log.warn(
            "Timezone validation warning for {}: DateTime {} is too far in past, possible timezone issue",
            fieldName, dateTime);
        return false;
      }

      log.debug("Timezone validation passed for {}: {}", fieldName, dateTime);
      return true;
    } catch (Exception e) {
      log.error("Error validating timezone for {}: {}", fieldName, e.getMessage());
      return false;
    }
  }

  /**
   * Get timezone offset between Vietnam and UTC at given time
   * 
   * @param localDateTime the local date time
   * @return offset in hours
   */
  public static int getVietnamUtcOffsetHours(LocalDateTime localDateTime) {
    if (localDateTime == null) {
      localDateTime = nowVietnam();
    }

    ZonedDateTime vietnamTime = localDateTime.atZone(VIETNAM_TIMEZONE);
    return vietnamTime.getOffset().getTotalSeconds() / 3600;
  }
}
