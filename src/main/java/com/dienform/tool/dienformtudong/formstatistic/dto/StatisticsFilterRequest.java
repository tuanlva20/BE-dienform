package com.dienform.tool.dienformtudong.formstatistic.dto;

import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatisticsFilterRequest {

  @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}",
      message = "Start date must be in format: yyyy-MM-dd HH:mm:ss")
  private String startDate;

  @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}",
      message = "End date must be in format: yyyy-MM-dd HH:mm:ss")
  private String endDate;

  private String formId;

  private String status;

  private String period; // "today", "week", "month", "year", "custom"
}
