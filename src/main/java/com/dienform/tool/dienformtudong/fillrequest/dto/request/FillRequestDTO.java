package com.dienform.tool.dienformtudong.fillrequest.dto.request;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
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

        @NotNull(message = "Percentage is required")
        @Min(value = 0, message = "Percentage must be non-negative")
        private Double percentage;

        private String valueString;

        private String rowId; // For matrix questions

        // Thêm trường positionIndex để đảm bảo tính nhất quán giữa các câu hỏi text
        private Integer positionIndex;
    }

    @NotNull(message = "Survey count is required")
    @Min(value = 1, message = "Survey count must be at least 1")
    private Integer surveyCount;

    @NotNull(message = "Price per survey is required")
    @Min(value = 0, message = "Price per survey must be non-negative")
    private BigDecimal pricePerSurvey;

    @NotNull(message = "Human-like flag is required")
    @JsonAlias({"humanLike", "isHumanLike"})
    @JsonProperty("isHumanLike")
    private Boolean isHumanLike;

    private String startDate;

    private String endDate;

    @NotNull(message = "Answer distributions are required")
    @Valid
    private List<AnswerDistributionRequest> answerDistributions;
}
