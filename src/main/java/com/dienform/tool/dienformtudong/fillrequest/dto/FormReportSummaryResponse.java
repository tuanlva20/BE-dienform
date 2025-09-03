package com.dienform.tool.dienformtudong.fillrequest.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormReportSummaryResponse {

  private Long totalForms;
  private Long totalCompletedForms;
  private Long totalProcessingForms;
  private Long totalFailedForms;
  private Long totalQueuedForms;
  private BigDecimal totalCost;
  private BigDecimal averageCostPerForm;
  private Long totalSurveys;
  private Long totalCompletedSurveys;
  private Long totalFailedSurveys;
  private BigDecimal completionRate; // Percentage
  private BigDecimal successRate; // Percentage
}
