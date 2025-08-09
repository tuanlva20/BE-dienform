package com.dienform.common.exception;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @Data
  @AllArgsConstructor
  public static class ErrorResponse {
    private int status;
    private String message;
    private String path;
    private LocalDateTime timestamp;
  }

  @Data
  @AllArgsConstructor
  public static class ValidationErrorResponse {
    private int status;
    private String message;
    private String path;
    private LocalDateTime timestamp;
    private Map<String, String> errors;
  }

  @ExceptionHandler(ResourceNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleResourceNotFoundException(ResourceNotFoundException ex,
      WebRequest request) {
    log.warn("Resource not found: {} - {}", ex.getResourceName(), ex.getMessage());

    // Create a more user-friendly error message
    String userMessage = String.format("Không tìm thấy %s với %s: '%s'", ex.getResourceName(),
        ex.getFieldName(), ex.getFieldValue());

    ErrorResponse errorResponse = new ErrorResponse(HttpStatus.NOT_FOUND.value(), userMessage,
        request.getDescription(false), LocalDateTime.now());
    return new ResponseEntity<>(errorResponse, HttpStatus.NOT_FOUND);
  }

  @ExceptionHandler(BadRequestException.class)
  public ResponseEntity<ErrorResponse> handleBadRequestException(BadRequestException ex,
      WebRequest request) {
    ErrorResponse errorResponse = new ErrorResponse(HttpStatus.BAD_REQUEST.value(), ex.getMessage(),
        request.getDescription(false), LocalDateTime.now());
    return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ValidationErrorResponse> handleMethodArgumentNotValidException(
      MethodArgumentNotValidException ex, WebRequest request) {
    Map<String, String> errors = new HashMap<>();
    ex.getBindingResult().getAllErrors().forEach(error -> {
      String fieldName = ((FieldError) error).getField();
      String errorMessage = error.getDefaultMessage();
      errors.put(fieldName, errorMessage);
    });

    ValidationErrorResponse errorResponse =
        new ValidationErrorResponse(HttpStatus.BAD_REQUEST.value(), "Validation error",
            request.getDescription(false), LocalDateTime.now(), errors);
    return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(BindException.class)
  public ResponseEntity<ValidationErrorResponse> handleBindException(BindException ex,
      WebRequest request) {
    Map<String, String> errors = new HashMap<>();
    ex.getBindingResult().getAllErrors().forEach(error -> {
      String fieldName = ((FieldError) error).getField();
      String errorMessage = error.getDefaultMessage();
      errors.put(fieldName, errorMessage);
    });

    ValidationErrorResponse errorResponse =
        new ValidationErrorResponse(HttpStatus.BAD_REQUEST.value(), "Validation error",
            request.getDescription(false), LocalDateTime.now(), errors);
    return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ErrorResponse> handleConstraintViolationException(
      ConstraintViolationException ex, WebRequest request) {
    ErrorResponse errorResponse = new ErrorResponse(HttpStatus.BAD_REQUEST.value(),
        "Validation error: " + ex.getMessage(), request.getDescription(false), LocalDateTime.now());
    return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleGlobalException(Exception ex, WebRequest request) {
    log.error("Unhandled exception", ex);
    ErrorResponse errorResponse = new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(),
        "An unexpected error occurred", request.getDescription(false), LocalDateTime.now());
    return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
  }

  // Detailed mapping error for user visibility
  @ExceptionHandler(com.dienform.tool.dienformtudong.exception.MappingException.class)
  public ResponseEntity<Map<String, Object>> handleMappingException(
      com.dienform.tool.dienformtudong.exception.MappingException ex, WebRequest request) {
    log.warn("MappingException: {} - details: {}", ex.getMessage(), ex.getDetails());
    Map<String, Object> body = new HashMap<>();
    body.put("status", HttpStatus.BAD_REQUEST.value());
    body.put("message", ex.getMessage());
    body.put("path", request.getDescription(false));
    body.put("timestamp", LocalDateTime.now());
    body.put("details", ex.getDetails());
    return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
  }
}
