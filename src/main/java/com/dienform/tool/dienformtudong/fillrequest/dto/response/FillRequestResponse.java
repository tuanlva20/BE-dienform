package com.dienform.tool.dienformtudong.fillrequest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillRequestResponse {
    private UUID id;
    private int surveyCount;
    private BigDecimal pricePerSurvey;
    private BigDecimal totalPrice;
    private boolean isHumanLike;
    private LocalDateTime createdAt;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private String status;
    private List<AnswerDistributionResponse> answerDistributions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnswerDistributionResponse {
        private UUID questionId;
        private UUID optionId;
        private int percentage;
        private int count;
        private String valueString;
        private OptionInfo option;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class OptionInfo {
            private UUID id;
            private String text;
        }
    }
}
