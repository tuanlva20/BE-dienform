package com.dienform.tool.dienformtudong.fillrequest.controller;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.dienform.common.model.ResponseModel;
import com.dienform.common.util.CurrentUserUtil;
import com.dienform.tool.dienformtudong.fillrequest.dto.FormReportPageResponse;
import com.dienform.tool.dienformtudong.fillrequest.dto.FormReportRequest;
import com.dienform.tool.dienformtudong.fillrequest.dto.FormReportSummaryResponse;
import com.dienform.tool.dienformtudong.fillrequest.service.FormReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/v1/form-reports")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class FormReportController {

  private final FormReportService formReportService;
  private final CurrentUserUtil currentUserUtil;

  /**
   * Get paginated form report with filtering GET /api/v1/form-reports
   */
  @GetMapping
  public ResponseEntity<ResponseModel<FormReportPageResponse>> getFormReport(
      @Valid FormReportRequest request) {
    try {
      // Lấy userId từ token hiện tại
      UUID currentUserId = currentUserUtil.requireCurrentUserId();
      request.setUserId(currentUserId);

      log.info("Received form report request for user {}: {}", currentUserId, request);
      FormReportPageResponse response = formReportService.getFormReport(request);
      return ResponseEntity.ok(ResponseModel.success(response, HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error getting form report: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(ResponseModel.error("Internal server error", HttpStatus.INTERNAL_SERVER_ERROR));
    }
  }

  /**
   * Get summary statistics for form report GET /api/v1/form-reports/summary
   */
  @GetMapping("/summary")
  public ResponseEntity<ResponseModel<FormReportSummaryResponse>> getFormReportSummary(
      @Valid FormReportRequest request) {
    try {
      // Lấy userId từ token hiện tại
      UUID currentUserId = currentUserUtil.requireCurrentUserId();
      request.setUserId(currentUserId);

      log.info("Received form report summary request for user {}: {}", currentUserId, request);
      FormReportSummaryResponse response = formReportService.getFormReportSummary(request);
      return ResponseEntity.ok(ResponseModel.success(response, HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error getting form report summary: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(ResponseModel.error("Internal server error", HttpStatus.INTERNAL_SERVER_ERROR));
    }
  }

  /**
   * Get form report with default parameters GET /api/v1/form-reports/default
   */
  @GetMapping("/default")
  public ResponseEntity<ResponseModel<FormReportPageResponse>> getDefaultFormReport() {
    try {
      // Lấy userId từ token hiện tại
      UUID currentUserId = currentUserUtil.requireCurrentUserId();

      log.info("Getting default form report for user: {}", currentUserId);
      FormReportRequest defaultRequest = FormReportRequest.builder().page(0).size(10)
          .sortBy("createdAt").sortDirection("desc").userId(currentUserId).build();

      FormReportPageResponse response = formReportService.getFormReport(defaultRequest);
      return ResponseEntity.ok(ResponseModel.success(response, HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error getting default form report: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(ResponseModel.error("Internal server error", HttpStatus.INTERNAL_SERVER_ERROR));
    }
  }

  /**
   * Get form report summary with default parameters GET /api/v1/form-reports/summary/default
   */
  @GetMapping("/summary/default")
  public ResponseEntity<ResponseModel<FormReportSummaryResponse>> getDefaultFormReportSummary() {
    try {
      // Lấy userId từ token hiện tại
      UUID currentUserId = currentUserUtil.requireCurrentUserId();

      log.info("Getting default form report summary for user: {}", currentUserId);
      FormReportRequest defaultRequest = FormReportRequest.builder().userId(currentUserId).build();

      FormReportSummaryResponse response = formReportService.getFormReportSummary(defaultRequest);
      return ResponseEntity.ok(ResponseModel.success(response, HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error getting default form report summary: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(ResponseModel.error("Internal server error", HttpStatus.INTERNAL_SERVER_ERROR));
    }
  }
}
