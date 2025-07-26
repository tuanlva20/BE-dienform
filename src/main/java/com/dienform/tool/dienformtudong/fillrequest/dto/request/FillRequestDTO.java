package com.dienform.tool.dienformtudong.fillrequest.dto.request;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillRequestDTO {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnswerDistributionRequest {
        @NotNull(message = "Question ID is required")
        private UUID questionId;

        private UUID optionId;

        // Thêm rowId để hỗ trợ grid/cell
        private UUID rowId;

        @NotNull(message = "Percentage is required")
        @Min(value = 0, message = "Percentage must be non-negative")
        private Integer percentage;

        private String valueString;
    }

    @NotNull(message = "Survey count is required")
    @Min(value = 1, message = "Survey count must be at least 1")
    private Integer surveyCount;

    @NotNull(message = "Price per survey is required")
    @Min(value = 0, message = "Price per survey must be non-negative")
    private BigDecimal pricePerSurvey;

    @NotNull(message = "Human-like flag is required")
    private Boolean isHumanLike;

    private LocalDateTime startDate;

    private LocalDateTime endDate;

    @NotNull(message = "Answer distributions are required")
    @Valid
    private List<AnswerDistributionRequest> answerDistributions;
}
