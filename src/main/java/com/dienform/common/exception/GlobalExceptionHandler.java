package com.dienform.common.exception;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import com.dienform.common.model.ErrorCode;
import com.dienform.common.model.ResponseModel;
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
  public ResponseEntity<ResponseModel<Void>> handleResourceNotFoundException(
      ResourceNotFoundException ex, WebRequest request) {
    log.warn("Resource not found: {} - {}", ex.getResourceName(), ex.getMessage());

    // Create a more user-friendly error message
    String userMessage = String.format("Không tìm thấy %s với %s: '%s'", ex.getResourceName(),
        ex.getFieldName(), ex.getFieldValue());

    Map<String, Object> details = new HashMap<>();
    details.put("path", request.getDescription(false));
    details.put("timestamp", LocalDateTime.now());
    return new ResponseEntity<>(
        ResponseModel.error(userMessage, HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, details),
        HttpStatus.NOT_FOUND);
  }

  @ExceptionHandler(BadRequestException.class)
  public ResponseEntity<ResponseModel<Void>> handleBadRequestException(BadRequestException ex,
      WebRequest request) {
    Map<String, Object> details = new HashMap<>();
    details.put("path", request.getDescription(false));
    details.put("timestamp", LocalDateTime.now());
    return new ResponseEntity<>(ResponseModel.error(ex.getMessage(), HttpStatus.BAD_REQUEST,
        ErrorCode.BAD_REQUEST, details), HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ResponseModel<Void>> handleMethodArgumentNotValidException(
      MethodArgumentNotValidException ex, WebRequest request) {
    Map<String, String> errors = new HashMap<>();
    ex.getBindingResult().getFieldErrors().forEach(error -> {
      String fieldName = error.getField();
      String errorMessage = error.getDefaultMessage();
      errors.put(fieldName, errorMessage);
    });

    Map<String, Object> details = new HashMap<>();
    details.put("errors", errors);
    details.put("path", request.getDescription(false));
    details.put("timestamp", LocalDateTime.now());
    return new ResponseEntity<>(ResponseModel.error("Validation error", HttpStatus.BAD_REQUEST,
        ErrorCode.VALIDATION_ERROR, details), HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(BindException.class)
  public ResponseEntity<ResponseModel<Void>> handleBindException(BindException ex,
      WebRequest request) {
    Map<String, String> errors = new HashMap<>();
    ex.getBindingResult().getFieldErrors().forEach(error -> {
      String fieldName = error.getField();
      String errorMessage = error.getDefaultMessage();
      errors.put(fieldName, errorMessage);
    });

    Map<String, Object> details = new HashMap<>();
    details.put("errors", errors);
    details.put("path", request.getDescription(false));
    details.put("timestamp", LocalDateTime.now());
    return new ResponseEntity<>(ResponseModel.error("Validation error", HttpStatus.BAD_REQUEST,
        ErrorCode.VALIDATION_ERROR, details), HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ResponseModel<Void>> handleConstraintViolationException(
      ConstraintViolationException ex, WebRequest request) {
    Map<String, Object> details = new HashMap<>();
    details.put("path", request.getDescription(false));
    details.put("timestamp", LocalDateTime.now());
    return new ResponseEntity<>(ResponseModel.error("Validation error: " + ex.getMessage(),
        HttpStatus.BAD_REQUEST, ErrorCode.CONSTRAINT_VIOLATION, details), HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ResponseModel<Void>> handleDataIntegrityViolationException(
      DataIntegrityViolationException ex, WebRequest request) {
    log.error("Data integrity violation: {}", ex.getMessage(), ex);

    String userMessage = "Dữ liệu không hợp lệ";
    if (ex.getMessage() != null) {
      if (ex.getMessage().contains("Data truncation")) {
        userMessage = "Dữ liệu quá dài cho một số trường. Vui lòng kiểm tra lại thông tin nhập.";
      } else if (ex.getMessage().contains("Duplicate entry")) {
        userMessage = "Dữ liệu đã tồn tại trong hệ thống.";
      } else if (ex.getMessage().contains("foreign key constraint")) {
        userMessage = "Dữ liệu không hợp lệ do vi phạm ràng buộc tham chiếu.";
      }
    }

    Map<String, Object> details = new HashMap<>();
    details.put("path", request.getDescription(false));
    details.put("timestamp", LocalDateTime.now());
    details.put("technicalError", ex.getMessage());
    return new ResponseEntity<>(ResponseModel.error(userMessage, HttpStatus.BAD_REQUEST,
        ErrorCode.DATA_INTEGRITY_VIOLATION, details), HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(ConflictException.class)
  public ResponseEntity<ResponseModel<Void>> handleConflictException(ConflictException ex,
      WebRequest request) {
    Map<String, Object> details = new HashMap<>();
    details.put("path", request.getDescription(false));
    details.put("timestamp", LocalDateTime.now());
    return new ResponseEntity<>(
        ResponseModel.error(ex.getMessage(), HttpStatus.CONFLICT, ErrorCode.CONFLICT, details),
        HttpStatus.CONFLICT);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ResponseModel<Void>> handleGlobalException(Exception ex,
      WebRequest request) {
    log.error("Unhandled exception", ex);
    Map<String, Object> details = new HashMap<>();
    details.put("path", request.getDescription(false));
    details.put("timestamp", LocalDateTime.now());
    return new ResponseEntity<>(ResponseModel.error("An unexpected error occurred",
        HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, details),
        HttpStatus.INTERNAL_SERVER_ERROR);
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
