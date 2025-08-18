package com.dienform.tool.dienformtudong.aisuggestion.exception;

import java.util.List;

/**
 * Exception thrown when input validation fails
 */
public class InvalidInputException extends AISuggestionException {

  private final List<String> validationErrors;

  public InvalidInputException(String message) {
    super("INVALID_INPUT", message);
    this.validationErrors = null;
  }

  public InvalidInputException(String message, List<String> validationErrors) {
    super("INVALID_INPUT", message);
    this.validationErrors = validationErrors;
  }

  public InvalidInputException(List<String> validationErrors) {
    super("INVALID_INPUT", "Validation failed: " + String.join(", ", validationErrors));
    this.validationErrors = validationErrors;
  }

  public List<String> getValidationErrors() {
    return validationErrors;
  }
}

