package com.dienform.tool.dienformtudong.formstatistic.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormStatisticResponse {
    private UUID id;
    private int totalSurvey;
    private int completedSurvey;
    private int failedSurvey;
    private int errorQuestion;
    private LocalDateTime updatedAt;
}
