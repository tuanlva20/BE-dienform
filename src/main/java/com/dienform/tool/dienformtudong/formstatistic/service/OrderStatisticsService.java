package com.dienform.tool.dienformtudong.formstatistic.service;

import org.springframework.data.domain.Pageable;
import com.dienform.tool.dienformtudong.formstatistic.dto.OrderStatisticsResponse;

public interface OrderStatisticsService {

  /**
   * Lấy thống kê payment orders của user hiện tại
   */
  OrderStatisticsResponse getOrderStatistics();

  /**
   * Lấy thống kê payment orders của user hiện tại với pagination
   */
  OrderStatisticsResponse getOrderStatistics(Pageable pageable);

  /**
   * Lấy thống kê payment orders của user hiện tại theo status
   */
  OrderStatisticsResponse getOrderStatisticsByStatus(String status);

  /**
   * Lấy thống kê payment orders của user hiện tại theo status với pagination
   */
  OrderStatisticsResponse getOrderStatisticsByStatus(String status, Pageable pageable);

  /**
   * Lấy thống kê payment orders của user hiện tại theo khoảng thời gian
   */
  OrderStatisticsResponse getOrderStatisticsByDateRange(String startDate, String endDate);

  /**
   * Lấy thống kê payment orders của user hiện tại theo khoảng thời gian với pagination
   */
  OrderStatisticsResponse getOrderStatisticsByDateRange(String startDate, String endDate,
      Pageable pageable);
}
