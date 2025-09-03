package com.dienform.tool.dienformtudong.formstatistic.service;

import java.util.UUID;
import com.dienform.tool.dienformtudong.formstatistic.dto.SurveyStatisticsResponse;

public interface SurveyStatisticsService {

  /**
   * Lấy thống kê tổng quan cho dashboard
   */
  SurveyStatisticsResponse getDashboardStatistics(UUID userId);

  /**
   * Lấy thống kê theo khoảng thời gian
   */
  SurveyStatisticsResponse getStatisticsByDateRange(String startDate, String endDate, UUID userId);

  /**
   * Lấy thống kê theo form ID
   */
  SurveyStatisticsResponse getStatisticsByFormId(String formId, UUID userId);
}
