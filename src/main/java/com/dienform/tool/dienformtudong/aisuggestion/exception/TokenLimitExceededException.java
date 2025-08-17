package com.dienform.tool.dienformtudong.aisuggestion.exception;

/**
 * Exception thrown when AI service token limit is exceeded
 */
public class TokenLimitExceededException extends AISuggestionException {

  private final Integer requestedTokens;
  private final Integer maxTokens;

  public TokenLimitExceededException(String message) {
    super("TOKEN_LIMIT_EXCEEDED", message);
    this.requestedTokens = null;
    this.maxTokens = null;
  }

  public TokenLimitExceededException(String message, Integer requestedTokens, Integer maxTokens) {
    super("TOKEN_LIMIT_EXCEEDED", message);
    this.requestedTokens = requestedTokens;
    this.maxTokens = maxTokens;
  }

  public TokenLimitExceededException(Integer requestedTokens, Integer maxTokens) {
    super("TOKEN_LIMIT_EXCEEDED",
        String.format("Token limit exceeded. Requested: %d, Max allowed: %d", requestedTokens, maxTokens));
    this.requestedTokens = requestedTokens;
    this.maxTokens = maxTokens;
  }

  public Integer getRequestedTokens() {
    return requestedTokens;
  }

  public Integer getMaxTokens() {
    return maxTokens;
  }
}

