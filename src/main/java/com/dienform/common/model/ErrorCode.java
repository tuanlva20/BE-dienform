package com.dienform.common.model;

/**
 * Standardized application error codes to keep FE handling consistent.
 */
public enum ErrorCode {
  INTERNAL_ERROR(1000, "An unexpected error occurred"),
  BAD_REQUEST(1001, "Bad request"),
  CONFLICT(1002, "Conflict"),
  NOT_FOUND(1003, "Not found"),
  VALIDATION_ERROR(1004, "Validation error"),
  CONSTRAINT_VIOLATION(1005, "Validation error"),
  UNAUTHORIZED(1006, "Unauthorized"),
  ENCODING_DATA_ERROR(1100, "Encoding data error");

  private final int code;
  private final String defaultMessage;

  ErrorCode(int code, String defaultMessage) {
    this.code = code;
    this.defaultMessage = defaultMessage;
  }

  public int getCode() {
    return code;
  }

  public String getDefaultMessage() {
    return defaultMessage;
  }
}



