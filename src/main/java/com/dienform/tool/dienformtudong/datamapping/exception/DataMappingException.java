package com.dienform.tool.dienformtudong.datamapping.exception;

public class DataMappingException extends RuntimeException {

  public DataMappingException(String message) {
    super(message);
  }

  public DataMappingException(String message, Throwable cause) {
    super(message, cause);
  }
}