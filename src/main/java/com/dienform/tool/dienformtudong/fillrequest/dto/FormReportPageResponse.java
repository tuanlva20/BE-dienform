package com.dienform.tool.dienformtudong.fillrequest.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormReportPageResponse {

  private List<FormReportResponse> content;
  private int pageNumber;
  private int pageSize;
  private long totalElements;
  private int totalPages;
  private boolean hasNext;
  private boolean hasPrevious;
  private boolean isFirst;
  private boolean isLast;
}
