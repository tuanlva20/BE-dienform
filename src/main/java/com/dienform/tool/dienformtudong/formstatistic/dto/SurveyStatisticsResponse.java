package com.dienform.tool.dienformtudong.formstatistic.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SurveyStatisticsResponse {

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class StatisticCard {
    private String title;
    private String icon;
    private String color;
    private Long count;
    private BigDecimal percentage;
    private String trend; // "up", "down", "stable"
    private BigDecimal trendPercentage;
    private String description;
    private BigDecimal progressBarPercentage;
  }

  private StatisticCard pendingSurveys;
  private StatisticCard successfulSurveys;
  private StatisticCard failedSurveys;
  private StatisticCard totalSurveys;

  // Thống kê theo thời gian
  private Long newSurveysToday;
  private Long completedSurveysThisWeek;
  private Long failedSurveysNeedReview;
  private Long totalSurveysRunSuccessfully;
}
