package com.dienform.tool.dienformtudong.aisuggestion.exception;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.dienform.common.model.ResponseModel;

import lombok.extern.slf4j.Slf4j;

/**
 * Global exception handler for AI Suggestion related exceptions
 */
@RestControllerAdvice
@Slf4j
public class AISuggestionExceptionHandler {

  @ExceptionHandler(AISuggestionException.class)
  public ResponseEntity<ResponseModel<Map<String, Object>>> handleAISuggestionException(AISuggestionException e) {
    log.error("AI Suggestion error: {}", e.getMessage(), e);

    Map<String, Object> errorDetails = new HashMap<>();
    errorDetails.put("errorCode", e.getErrorCode());
    errorDetails.put("message", e.getMessage());
    errorDetails.put("args", e.getArgs());
    errorDetails.put("timestamp", System.currentTimeMillis());

    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ResponseModel.error("AI service error occurred", HttpStatus.INTERNAL_SERVER_ERROR));
  }

  @ExceptionHandler(TokenLimitExceededException.class)
  public ResponseEntity<ResponseModel<Map<String, Object>>> handleTokenLimitExceededException(
      TokenLimitExceededException e) {
    log.warn("Token limit exceeded: {}", e.getMessage());

    Map<String, Object> errorDetails = new HashMap<>();
    errorDetails.put("errorCode", e.getErrorCode());
    errorDetails.put("message", e.getMessage());
    errorDetails.put("requestedTokens", e.getRequestedTokens());
    errorDetails.put("maxTokens", e.getMaxTokens());
    errorDetails.put("timestamp", System.currentTimeMillis());

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ResponseModel.error("Token limit exceeded", HttpStatus.BAD_REQUEST));
  }

  @ExceptionHandler(InvalidInputException.class)
  public ResponseEntity<ResponseModel<Map<String, Object>>> handleInvalidInputException(InvalidInputException e) {
    log.warn("Invalid input: {}", e.getMessage());

    Map<String, Object> errorDetails = new HashMap<>();
    errorDetails.put("errorCode", e.getErrorCode());
    errorDetails.put("message", e.getMessage());
    errorDetails.put("validationErrors", e.getValidationErrors());
    errorDetails.put("timestamp", System.currentTimeMillis());

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ResponseModel.error("Invalid input provided", HttpStatus.BAD_REQUEST));
  }

  @ExceptionHandler(RuntimeException.class)
  public ResponseEntity<ResponseModel<Map<String, Object>>> handleRuntimeException(RuntimeException e) {
    log.error("Unexpected runtime error in AI Suggestion: {}", e.getMessage(), e);

    Map<String, Object> errorDetails = new HashMap<>();
    errorDetails.put("errorCode", "UNEXPECTED_ERROR");
    errorDetails.put("message", "An unexpected error occurred");
    errorDetails.put("timestamp", System.currentTimeMillis());

    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ResponseModel.error("Unexpected error occurred", HttpStatus.INTERNAL_SERVER_ERROR));
  }
}
