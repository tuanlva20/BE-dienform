package com.dienform.tool.dienformtudong.exception;

import java.util.Map;

/**
 * Thrown when a question-to-element mapping cannot be resolved reliably.
 */
public class MappingException extends RuntimeException {
  private final Map<String, Object> details;

  public MappingException(String message, Map<String, Object> details) {
    super(message);
    this.details = details;
  }

  public Map<String, Object> getDetails() {
    return details;
  }
}


