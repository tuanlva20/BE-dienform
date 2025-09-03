package com.dienform.tool.dienformtudong.fillrequest.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.tool.dienformtudong.fillrequest.dto.FormReportPageResponse;
import com.dienform.tool.dienformtudong.fillrequest.dto.FormReportRequest;
import com.dienform.tool.dienformtudong.fillrequest.dto.FormReportResponse;
import com.dienform.tool.dienformtudong.fillrequest.dto.FormReportSummaryResponse;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.fillrequest.mapper.FormReportMapper;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestRepository;
import com.dienform.tool.dienformtudong.fillrequest.service.FormReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class FormReportServiceImpl implements FormReportService {

  private final FillRequestRepository fillRequestRepository;
  private final FormReportMapper formReportMapper;

  @Override
  public FormReportPageResponse getFormReport(FormReportRequest request) {
    log.info("Getting form report with request: {}", request);

    // Validate userId is present
    if (request.getUserId() == null) {
      throw new IllegalArgumentException("UserId is required for form report");
    }

    // Create pageable with sorting
    Sort sort = Sort.by(Sort.Direction.fromString(request.getSortDirection().toUpperCase()),
        request.getSortBy());
    Pageable pageable = PageRequest.of(request.getPage(), request.getSize(), sort);

    // Get data from repository with userId filter
    List<FillRequest> fillRequests = fillRequestRepository.findForReport(request.getUserId(),
        request.getSearchTerm(), request.getStatus(), request.getFormStatus(),
        request.getFormType(), request.getStartDate(), request.getEndDate(), pageable);

    // Count total elements with userId filter
    long totalElements = fillRequestRepository.countForReport(request.getUserId(),
        request.getSearchTerm(), request.getStatus(), request.getFormStatus(),
        request.getFormType(), request.getStartDate(), request.getEndDate());

    // Map to response DTOs and set type field
    List<FormReportResponse> content = fillRequests.stream().map(fillRequest -> {
      FormReportResponse response = formReportMapper.toFormReportResponse(fillRequest);
      // Set type based on answerDistributions
      if (fillRequest.getAnswerDistributions() != null
          && !fillRequest.getAnswerDistributions().isEmpty()) {
        response.setType("Điền theo tỉ lệ");
      } else {
        response.setType("Điền theo data");
      }
      return response;
    }).collect(Collectors.toList());

    // Calculate pagination info
    int totalPages = (int) Math.ceil((double) totalElements / request.getSize());
    boolean hasNext = request.getPage() < totalPages - 1;
    boolean hasPrevious = request.getPage() > 0;
    boolean isFirst = request.getPage() == 0;
    boolean isLast = request.getPage() >= totalPages - 1;

    return FormReportPageResponse.builder().content(content).pageNumber(request.getPage())
        .pageSize(request.getSize()).totalElements(totalElements).totalPages(totalPages)
        .hasNext(hasNext).hasPrevious(hasPrevious).isFirst(isFirst).isLast(isLast).build();
  }

  @Override
  public FormReportSummaryResponse getFormReportSummary(FormReportRequest request) {
    log.info("Getting form report summary with request: {}", request);

    // Validate userId is present
    if (request.getUserId() == null) {
      throw new IllegalArgumentException("UserId is required for form report summary");
    }

    // Get summary statistics from repository with userId filter
    Object[] stats = fillRequestRepository.getSummaryStatistics(request.getUserId(),
        request.getSearchTerm(), request.getStatus(), request.getFormStatus(),
        request.getFormType(), request.getStartDate(), request.getEndDate());

    if (stats == null || stats.length < 10) {
      log.warn("Invalid statistics data returned from repository");
      return createEmptySummary();
    }

    try {
      // Extract statistics from Object array
      Long totalForms = getLongValue(stats[0]);
      Long totalCompletedForms = getLongValue(stats[1]);
      Long totalProcessingForms = getLongValue(stats[2]);
      Long totalFailedForms = getLongValue(stats[3]);
      Long totalQueuedForms = getLongValue(stats[4]);
      BigDecimal totalCost = getBigDecimalValue(stats[5]);
      BigDecimal averageCostPerForm = getBigDecimalValue(stats[6]);
      Long totalSurveys = getLongValue(stats[7]);
      Long totalCompletedSurveys = getLongValue(stats[8]);
      Long totalFailedSurveys = getLongValue(stats[9]);

      // Calculate rates
      BigDecimal completionRate = calculateRate(totalSurveys, totalCompletedSurveys);
      BigDecimal successRate = calculateRate(totalSurveys, totalCompletedSurveys);

      return FormReportSummaryResponse.builder().totalForms(totalForms != null ? totalForms : 0L)
          .totalCompletedForms(totalCompletedForms != null ? totalCompletedForms : 0L)
          .totalProcessingForms(totalProcessingForms != null ? totalProcessingForms : 0L)
          .totalFailedForms(totalFailedForms != null ? totalFailedForms : 0L)
          .totalQueuedForms(totalQueuedForms != null ? totalQueuedForms : 0L)
          .totalCost(totalCost != null ? totalCost : BigDecimal.ZERO)
          .averageCostPerForm(averageCostPerForm != null ? averageCostPerForm : BigDecimal.ZERO)
          .totalSurveys(totalSurveys != null ? totalSurveys : 0L)
          .totalCompletedSurveys(totalCompletedSurveys != null ? totalCompletedSurveys : 0L)
          .totalFailedSurveys(totalFailedSurveys != null ? totalFailedSurveys : 0L)
          .completionRate(completionRate).successRate(successRate).build();

    } catch (Exception e) {
      log.error("Error processing summary statistics: {}", e.getMessage(), e);
      return createEmptySummary();
    }
  }

  private Long getLongValue(Object value) {
    if (value == null)
      return 0L;
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    try {
      return Long.parseLong(value.toString());
    } catch (NumberFormatException e) {
      log.warn("Could not parse long value: {}", value);
      return 0L;
    }
  }

  private BigDecimal getBigDecimalValue(Object value) {
    if (value == null)
      return BigDecimal.ZERO;
    if (value instanceof BigDecimal) {
      return (BigDecimal) value;
    }
    if (value instanceof Number) {
      return BigDecimal.valueOf(((Number) value).doubleValue());
    }
    try {
      return new BigDecimal(value.toString());
    } catch (NumberFormatException e) {
      log.warn("Could not parse BigDecimal value: {}", value);
      return BigDecimal.ZERO;
    }
  }

  private BigDecimal calculateRate(Long total, Long completed) {
    if (total == null || total == 0 || completed == null) {
      return BigDecimal.ZERO;
    }
    return BigDecimal.valueOf(completed).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100));
  }

  private FormReportSummaryResponse createEmptySummary() {
    return FormReportSummaryResponse.builder().totalForms(0L).totalCompletedForms(0L)
        .totalProcessingForms(0L).totalFailedForms(0L).totalQueuedForms(0L)
        .totalCost(BigDecimal.ZERO).averageCostPerForm(BigDecimal.ZERO).totalSurveys(0L)
        .totalCompletedSurveys(0L).totalFailedSurveys(0L).completionRate(BigDecimal.ZERO)
        .successRate(BigDecimal.ZERO).build();
  }
}
