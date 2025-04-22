package com.dienform.tool.dienformtudong.fillrequest.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillRequestDTO {
    
    @NotNull(message = "Survey count is required")
    @Min(value = 1, message = "Survey count must be at least 1")
    private Integer surveyCount;
    
    @NotNull(message = "Price per survey is required")
    @Min(value = 0, message = "Price per survey must be non-negative")
    private BigDecimal pricePerSurvey;
    
    @NotNull(message = "Human-like flag is required")
    private Boolean isHumanLike;
    
    @NotNull(message = "Answer distributions are required")
    @Valid
    private List<AnswerDistributionRequest> answerDistributions;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnswerDistributionRequest {
        @NotNull(message = "Question ID is required")
        private UUID questionId;
        
        @NotNull(message = "Option ID is required")
        private UUID optionId;
        
        @NotNull(message = "Percentage is required")
        @Min(value = 0, message = "Percentage must be non-negative")
        private Integer percentage;
    }
}