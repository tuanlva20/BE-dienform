package com.dienform.realtime.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillRequestUpdateEvent {
  private String formId;
  private String requestId;
  private String status;
  private int completedSurvey;
  private int surveyCount;
  private String updatedAt;
}



