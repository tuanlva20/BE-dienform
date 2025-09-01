package com.dienform.tool.dienformtudong.formstatistic.service;

import com.dienform.tool.dienformtudong.formstatistic.dto.SurveyStatisticsResponse;

public interface SurveyStatisticsService {

  /**
   * Lấy thống kê tổng quan cho dashboard
   */
  SurveyStatisticsResponse getDashboardStatistics();

  /**
   * Lấy thống kê theo khoảng thời gian
   */
  SurveyStatisticsResponse getStatisticsByDateRange(String startDate, String endDate);

  /**
   * Lấy thống kê theo form ID
   */
  SurveyStatisticsResponse getStatisticsByFormId(String formId);
}
