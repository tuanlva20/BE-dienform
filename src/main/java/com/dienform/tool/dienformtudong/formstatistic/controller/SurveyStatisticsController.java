package com.dienform.tool.dienformtudong.formstatistic.controller;

import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.dienform.common.model.ResponseModel;
import com.dienform.common.util.CurrentUserUtil;
import com.dienform.tool.dienformtudong.formstatistic.dto.OrderStatisticsResponse;
import com.dienform.tool.dienformtudong.formstatistic.dto.SurveyStatisticsResponse;
import com.dienform.tool.dienformtudong.formstatistic.service.OrderStatisticsService;
import com.dienform.tool.dienformtudong.formstatistic.service.SurveyStatisticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/v1/statistics")
@RequiredArgsConstructor
@Slf4j
public class SurveyStatisticsController {

  private final SurveyStatisticsService surveyStatisticsService;
  private final OrderStatisticsService orderStatisticsService;
  private final CurrentUserUtil currentUserUtil;

  /**
   * Lấy thống kê tổng quan cho dashboard GET /api/v1/statistics/dashboard
   */
  @GetMapping("/dashboard")
  public ResponseEntity<ResponseModel<SurveyStatisticsResponse>> getDashboardStatistics() {
    try {
      // Lấy userId từ token hiện tại
      UUID currentUserId = currentUserUtil.requireCurrentUserId();

      log.info("Getting dashboard statistics for user: {}", currentUserId);
      SurveyStatisticsResponse response =
          surveyStatisticsService.getDashboardStatistics(currentUserId);
      return ResponseEntity.ok(ResponseModel.success(response, HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error getting dashboard statistics: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(ResponseModel.error(e.getMessage(), HttpStatus.BAD_REQUEST));
    }
  }

  /**
   * Lấy thống kê theo khoảng thời gian GET /api/v1/statistics/range?startDate=2024-01-01
   * 00:00:00&endDate=2024-01-31 23:59:59
   */
  @GetMapping("/range")
  public ResponseEntity<ResponseModel<SurveyStatisticsResponse>> getStatisticsByDateRange(
      @RequestParam String startDate, @RequestParam String endDate) {
    try {
      // Lấy userId từ token hiện tại
      UUID currentUserId = currentUserUtil.requireCurrentUserId();

      log.info("Getting statistics for date range: {} to {} for user: {}", startDate, endDate,
          currentUserId);
      SurveyStatisticsResponse response =
          surveyStatisticsService.getStatisticsByDateRange(startDate, endDate, currentUserId);
      return ResponseEntity.ok(ResponseModel.success(response, HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error getting statistics by date range: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(ResponseModel.error(e.getMessage(), HttpStatus.BAD_REQUEST));
    }
  }

  /**
   * Lấy thống kê theo form ID GET /api/v1/statistics/form/{formId}
   */
  @GetMapping("/form/{formId}")
  public ResponseEntity<ResponseModel<SurveyStatisticsResponse>> getStatisticsByFormId(
      @PathVariable String formId) {
    try {
      // Lấy userId từ token hiện tại
      UUID currentUserId = currentUserUtil.requireCurrentUserId();

      log.info("Getting statistics for form ID: {} for user: {}", formId, currentUserId);
      SurveyStatisticsResponse response =
          surveyStatisticsService.getStatisticsByFormId(formId, currentUserId);
      return ResponseEntity.ok(ResponseModel.success(response, HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error getting statistics for form {}: {}", formId, e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(ResponseModel.error(e.getMessage(), HttpStatus.BAD_REQUEST));
    }
  }

  /**
   * Lấy thống kê nhanh (chỉ số liệu cơ bản) GET /api/v1/statistics/quick
   */
  @GetMapping("/quick")
  public ResponseEntity<ResponseModel<SurveyStatisticsResponse>> getQuickStatistics() {
    try {
      // Lấy userId từ token hiện tại
      UUID currentUserId = currentUserUtil.requireCurrentUserId();

      log.info("Getting quick statistics for user: {}", currentUserId);
      SurveyStatisticsResponse response =
          surveyStatisticsService.getDashboardStatistics(currentUserId);
      return ResponseEntity.ok(ResponseModel.success(response, HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error getting quick statistics: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(ResponseModel.error(e.getMessage(), HttpStatus.BAD_REQUEST));
    }
  }

  // ========== ORDER STATISTICS ENDPOINTS ==========

  /**
   * Lấy thống kê payment orders của user hiện tại GET /api/v1/statistics/orders
   */
  @GetMapping("/orders")
  public ResponseEntity<ResponseModel<OrderStatisticsResponse>> getOrderStatistics(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
    try {
      log.info("Getting order statistics for current user with pagination - page: {}, size: {}",
          page, size);

      Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
      OrderStatisticsResponse response = orderStatisticsService.getOrderStatistics(pageable);

      return ResponseEntity.ok(ResponseModel.success(response, HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error getting order statistics: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(ResponseModel.error(e.getMessage(), HttpStatus.BAD_REQUEST));
    }
  }

  /**
   * Lấy thống kê payment orders của user hiện tại theo status GET
   * /api/v1/statistics/orders/status/{status}
   */
  @GetMapping("/orders/status/{status}")
  public ResponseEntity<ResponseModel<OrderStatisticsResponse>> getOrderStatisticsByStatus(
      @PathVariable String status, @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "10") int size) {
    try {
      log.info(
          "Getting order statistics for current user with status: {} and pagination - page: {}, size: {}",
          status, page, size);

      Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
      OrderStatisticsResponse response =
          orderStatisticsService.getOrderStatisticsByStatus(status, pageable);

      return ResponseEntity.ok(ResponseModel.success(response, HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error getting order statistics with status {}: {}", status, e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(ResponseModel.error(e.getMessage(), HttpStatus.BAD_REQUEST));
    }
  }

  /**
   * Lấy thống kê payment orders của user hiện tại theo khoảng thời gian GET
   * /api/v1/statistics/orders/range?startDate=2024-01-01 00:00:00&endDate=2024-01-31 23:59:59
   */
  @GetMapping("/orders/range")
  public ResponseEntity<ResponseModel<OrderStatisticsResponse>> getOrderStatisticsByDateRange(
      @RequestParam String startDate, @RequestParam String endDate,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
    try {
      log.info(
          "Getting order statistics for current user from {} to {} with pagination - page: {}, size: {}",
          startDate, endDate, page, size);

      Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
      OrderStatisticsResponse response =
          orderStatisticsService.getOrderStatisticsByDateRange(startDate, endDate, pageable);

      return ResponseEntity.ok(ResponseModel.success(response, HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error getting order statistics by date range: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(ResponseModel.error(e.getMessage(), HttpStatus.BAD_REQUEST));
    }
  }
}
