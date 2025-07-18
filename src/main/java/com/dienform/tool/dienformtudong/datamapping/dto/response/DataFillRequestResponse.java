package com.dienform.tool.dienformtudong.datamapping.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataFillRequestResponse {

  private String id;
  private String status;
  private String message;
  private Integer estimatedTime;
}
