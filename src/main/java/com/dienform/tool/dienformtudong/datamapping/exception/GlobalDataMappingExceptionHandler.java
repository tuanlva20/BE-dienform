package com.dienform.tool.dienformtudong.datamapping.exception;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import com.dienform.tool.dienformtudong.datamapping.dto.response.DataFillRequestResponse;
import com.dienform.tool.dienformtudong.datamapping.dto.response.DataMappingResponse;

import lombok.extern.slf4j.Slf4j;

@RestControllerAdvice
@Slf4j
public class GlobalDataMappingExceptionHandler {

  @ExceptionHandler(RateLimitExceededException.class)
  public ResponseEntity<DataMappingResponse> handleRateLimitExceeded(RateLimitExceededException e) {
    log.warn("Rate limit exceeded: {}", e.getMessage());

    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header("X-RateLimit-Retry-After", String.valueOf(e.getRetryAfterSeconds()))
        .body(DataMappingResponse.builder()
            .errors(Arrays.asList(e.getMessage()))
            .build());
  }

  @ExceptionHandler(DataMappingException.class)
  public ResponseEntity<DataMappingResponse> handleDataMappingException(DataMappingException e) {
    log.error("Data mapping error: {}", e.getMessage(), e);

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(DataMappingResponse.builder()
            .errors(Arrays.asList(e.getMessage()))
            .build());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<DataMappingResponse> handleValidationException(MethodArgumentNotValidException e) {
    List<String> errors = e.getBindingResult().getFieldErrors().stream()
        .map(FieldError::getDefaultMessage)
        .collect(Collectors.toList());

    log.error("Validation errors: {}", errors);

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(DataMappingResponse.builder()
            .errors(errors)
            .build());
  }

  @ExceptionHandler({ RestClientException.class, ResourceAccessException.class })
  public ResponseEntity<DataMappingResponse> handleRestClientException(Exception e) {
    log.error("External service error: {}", e.getMessage(), e);

    String errorMessage = "Không thể kết nối đến Google Sheets. Vui lòng kiểm tra link và thử lại.";

    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(DataMappingResponse.builder()
            .errors(Arrays.asList(errorMessage))
            .build());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<DataMappingResponse> handleIllegalArgumentException(IllegalArgumentException e) {
    log.error("Invalid argument: {}", e.getMessage(), e);

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(DataMappingResponse.builder()
            .errors(Arrays.asList("Tham số không hợp lệ: " + e.getMessage()))
            .build());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<DataFillRequestResponse> handleGenericException(Exception e) {
    log.error("Unexpected error: {}", e.getMessage(), e);

    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(DataFillRequestResponse.builder()
            .status("FAILED")
            .message("Có lỗi hệ thống xảy ra. Vui lòng thử lại sau.")
            .build());
  }
}