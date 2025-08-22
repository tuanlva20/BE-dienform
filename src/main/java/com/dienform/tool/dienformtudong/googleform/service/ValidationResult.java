package com.dienform.tool.dienformtudong.googleform.service;

import java.util.List;

/**
 * Validation result for required questions
 */
public class ValidationResult {
  private final boolean valid;
  private final String errorMessage;
  private final List<String> missingRequiredQuestions;

  public ValidationResult(boolean valid, String errorMessage,
      List<String> missingRequiredQuestions) {
    this.valid = valid;
    this.errorMessage = errorMessage;
    this.missingRequiredQuestions = missingRequiredQuestions;
  }

  public boolean isValid() {
    return valid;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public List<String> getMissingRequiredQuestions() {
    return missingRequiredQuestions;
  }

  public static ValidationResult success() {
    return new ValidationResult(true, null, null);
  }

  public static ValidationResult failure(String message, List<String> missingQuestions) {
    return new ValidationResult(false, message, missingQuestions);
  }
}

