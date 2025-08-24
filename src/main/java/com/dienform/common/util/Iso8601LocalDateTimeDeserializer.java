package com.dienform.common.util;

import java.io.IOException;
import java.time.LocalDateTime;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import lombok.extern.slf4j.Slf4j;

/**
 * Custom Jackson deserializer for ISO 8601 datetime strings Handles various ISO 8601 formats
 * including: - "2025-08-24T17:00:00.000Z" (UTC with Z suffix) - "2025-08-24T17:00:00.000+07:00"
 * (with timezone offset) - "2025-08-24T17:00:00.000" (without timezone)
 */
@Slf4j
public class Iso8601LocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

  @Override
  public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    String value = p.getValueAsString();

    if (value == null || value.trim().isEmpty()) {
      return null;
    }

    try {
      return DateTimeUtil.parseIso8601ToVietnamTime(value);
    } catch (Exception e) {
      log.warn("Failed to parse ISO 8601 datetime: {}, error: {}", value, e.getMessage());
      throw new IOException("Invalid ISO 8601 datetime format: " + value, e);
    }
  }
}
