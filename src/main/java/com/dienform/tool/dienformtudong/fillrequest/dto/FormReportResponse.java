package com.dienform.tool.dienformtudong.fillrequest.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum;
import com.dienform.tool.dienformtudong.form.enums.FormStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormReportResponse {

  private UUID id;
  private String formName;
  private String formType; // Auto/Manual
  private String type; // "Điền theo tỉ lệ" hoặc "Điền theo data"
  private LocalDateTime createdAt;
  private FillRequestStatusEnum status;
  private String statusDisplayName;
  private String completionProgress; // "85/150"
  private BigDecimal totalCost;
  private BigDecimal costPerSurvey;
  private Integer surveyCount;
  private Integer completedSurvey;
  private Integer failedSurvey;
  private String formUrl;
  private FormStatusEnum formStatus;
  private String formStatusDisplayName;
  private LocalDateTime startDate;
  private LocalDateTime endDate;
  private LocalDateTime estimatedCompletionDate;
  private Integer priority;
  private Integer queuePosition;
  private LocalDateTime queuedAt;
  private Integer retryCount;
  private Integer maxRetries;
}
