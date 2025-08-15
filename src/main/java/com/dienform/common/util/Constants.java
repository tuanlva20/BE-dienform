package com.dienform.common.util;

public class Constants {
  // Form status constants
  public static final String FORM_STATUS_CREATED = "CREATED";
  public static final String FORM_STATUS_PROCESSING = "PROCESSING";
  public static final String FORM_STATUS_COMPLETED = "COMPLETED";
  public static final String FORM_STATUS_FAILED = "FAILED";

  // Fill request status constants
  public static final String FILL_REQUEST_STATUS_PENDING = "PENDING";
  public static final String FILL_REQUEST_STATUS_RUNNING = "IN_PROCESS"; // Legacy usage; map to enum
  public static final String FILL_REQUEST_STATUS_COMPLETED = "COMPLETED";
  public static final String FILL_REQUEST_STATUS_FAILED = "FAILED";
  public static final String FILL_REQUEST_STATUS_CANCELLED = "CANCELLED";

  // Survey execution status constants
  public static final String EXECUTION_STATUS_PENDING = "PENDING";
  public static final String EXECUTION_STATUS_COMPLETED = "COMPLETED";
  public static final String EXECUTION_STATUS_FAILED = "FAILED";

  // Question types
  public static final String QUESTION_TYPE_TEXT = "TEXT";
  public static final String QUESTION_TYPE_CHECKBOX = "CHECKBOX";
  public static final String QUESTION_TYPE_RADIO = "RADIO";
  public static final String QUESTION_TYPE_DROPDOWN = "DROPDOWN";
  public static final String QUESTION_TYPE_DATE = "DATE";
  public static final String QUESTION_TYPE_TIME = "TIME";
  public static final String QUESTION_TYPE_RATING = "RATING";

  // Pagination defaults
  public static final int DEFAULT_PAGE_SIZE = 10;
  public static final int DEFAULT_PAGE_NUMBER = 0;
  public static final String EXECUTION_STATUS_SUCCESS = null;
}
