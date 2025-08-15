package com.dienform.tool.dienformtudong.fillrequest.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillRequestResponse {
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnswerDistributionResponse {
        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class OptionInfo {
            private UUID id;
            private String text;
        }

        private UUID questionId;
        private UUID optionId;
        private int percentage;
        private int count;
        private String valueString;
        private String rowId; // For matrix questions

        private Integer positionIndex; // Để mapping vị trí giữa các câu hỏi text

        private OptionInfo option;
    }

    private UUID id;
    private int surveyCount;
    private BigDecimal pricePerSurvey;
    private BigDecimal totalPrice;
    private boolean isHumanLike;
    private LocalDateTime createdAt;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private String status;

    private int completedSurvey;

    private List<AnswerDistributionResponse> answerDistributions;
}
