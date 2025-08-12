package com.dienform.tool.mahoa.service.model;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EncodeResult {
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class RowError {
    private int rowNumber;
    @Builder.Default
    private List<CellError> cellErrors = new ArrayList<>();
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class CellError {
    private String columnName;
    private String questionTitle;
    private String gridRowTitle; // nullable
    private String providedValue;
    private String message;
  }

  private byte[] excelBytes;

  @Builder.Default
  private List<RowError> rowErrors = new ArrayList<>();

  public boolean hasErrors() {
    return rowErrors != null && !rowErrors.isEmpty();
  }
}


