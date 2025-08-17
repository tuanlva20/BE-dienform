package com.dienform.tool.dienformtudong.aisuggestion.exception;

/**
 * Base exception for AI Suggestion related errors
 */
public class AISuggestionException extends RuntimeException {

  private final String errorCode;
  private final Object[] args;

  public AISuggestionException(String message) {
    super(message);
    this.errorCode = "AI_SUGGESTION_ERROR";
    this.args = new Object[0];
  }

  public AISuggestionException(String message, Throwable cause) {
    super(message, cause);
    this.errorCode = "AI_SUGGESTION_ERROR";
    this.args = new Object[0];
  }

  public AISuggestionException(String errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
    this.args = new Object[0];
  }

  public AISuggestionException(String errorCode, String message, Object... args) {
    super(message);
    this.errorCode = errorCode;
    this.args = args;
  }

  public AISuggestionException(String errorCode, String message, Throwable cause, Object... args) {
    super(message, cause);
    this.errorCode = errorCode;
    this.args = args;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public Object[] getArgs() {
    return args;
  }
}

