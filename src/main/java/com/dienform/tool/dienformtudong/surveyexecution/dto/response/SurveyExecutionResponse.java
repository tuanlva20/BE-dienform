package com.dienform.tool.dienformtudong.surveyexecution.dto.response;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SurveyExecutionResponse {

    private UUID id;
    private UUID fillRequestId;
    private LocalDateTime executionTime;
    private String status;
    private String errorMessage;
    private Map<String, Object> responseData;
}
