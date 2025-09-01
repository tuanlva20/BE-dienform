package com.dienform.tool.dienformtudong.formstatistic.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;
import com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum;
import com.dienform.tool.dienformtudong.formstatistic.dto.SurveyStatisticsResponse;
import com.dienform.tool.dienformtudong.formstatistic.repository.SurveyStatisticsRepository;
import com.dienform.tool.dienformtudong.formstatistic.service.SurveyStatisticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class SurveyStatisticsServiceImpl implements SurveyStatisticsService {

  private final SurveyStatisticsRepository surveyStatisticsRepository;

  @Override
  public SurveyStatisticsResponse getDashboardStatistics() {
    log.info("Calculating dashboard statistics");

    try {
      // Tính toán các thống kê cơ bản
      Long pendingCount = surveyStatisticsRepository.countByStatus(FillRequestStatusEnum.QUEUED);
      Long inProcessCount = surveyStatisticsRepository.countByStatus(FillRequestStatusEnum.IN_PROCESS);
      Long completedCount = surveyStatisticsRepository.countByStatus(FillRequestStatusEnum.COMPLETED);
      Long failedCount = surveyStatisticsRepository.countByStatus(FillRequestStatusEnum.FAILED);

      // Tổng số khảo sát
      Long totalCount = pendingCount + inProcessCount + completedCount + failedCount;

      // Thống kê theo thời gian
      Long newSurveysToday = surveyStatisticsRepository.countNewSurveysToday();
      Long completedThisWeek =
          surveyStatisticsRepository.countCompletedSurveysThisWeek(getStartOfWeek());
      Long failedNeedReview = surveyStatisticsRepository.countFailedSurveysNeedReview();
      Long totalRunSuccessfully = surveyStatisticsRepository.countTotalSurveysRunSuccessfully();

      // Tính toán trend (so sánh với tuần trước)
      LocalDateTime lastWeekStart = getStartOfWeek().minusWeeks(1);
      LocalDateTime lastWeekEnd = getStartOfWeek().minusSeconds(1);

      Long lastWeekCompleted = surveyStatisticsRepository
          .countByStatusAndDateRange(FillRequestStatusEnum.COMPLETED, lastWeekStart, lastWeekEnd);
      Long lastWeekFailed = surveyStatisticsRepository
          .countByStatusAndDateRange(FillRequestStatusEnum.FAILED, lastWeekStart, lastWeekEnd);
      Long lastWeekPending = surveyStatisticsRepository
          .countByStatusAndDateRange(FillRequestStatusEnum.QUEUED, lastWeekStart, lastWeekEnd);

      // Tính phần trăm và trend
      BigDecimal pendingPercentage = calculatePercentage(pendingCount + inProcessCount, totalCount);
      BigDecimal successfulPercentage = calculatePercentage(completedCount, totalCount);
      BigDecimal failedPercentage = calculatePercentage(failedCount, totalCount);
      BigDecimal totalPercentage = calculatePercentage(totalRunSuccessfully, totalCount);

      // Tính trend percentage
      BigDecimal completedTrend = calculateTrendPercentage(completedCount, lastWeekCompleted);
      BigDecimal failedTrend = calculateTrendPercentage(failedCount, lastWeekFailed);
      BigDecimal pendingTrend =
          calculateTrendPercentage(pendingCount + inProcessCount, lastWeekPending);
      BigDecimal totalTrend = calculateTrendPercentage(totalRunSuccessfully, lastWeekCompleted);

      return SurveyStatisticsResponse.builder()
          .pendingSurveys(createStatisticCard("Khảo sát chờ xử lý", "clock", "#FFA500",
              pendingCount + inProcessCount, pendingPercentage, getTrendDirection(pendingTrend),
              pendingTrend, newSurveysToday + " khảo sát mới hôm nay", pendingPercentage))
          .successfulSurveys(
              createStatisticCard("Khảo sát thành công", "check", "#28a745", completedCount,
                  successfulPercentage, getTrendDirection(completedTrend), completedTrend,
                  completedThisWeek + " khảo sát hoàn thành tuần này", successfulPercentage))
          .failedSurveys(createStatisticCard("Khảo sát thất bại", "x", "#dc3545", failedCount,
              failedPercentage, getTrendDirection(failedTrend), failedTrend,
              failedNeedReview + " khảo sát lỗi cần xem lại", failedPercentage))
          .totalSurveys(createStatisticCard("Tổng số khảo sát", "file-text", "#6f42c1", totalCount,
              totalPercentage, getTrendDirection(totalTrend), totalTrend,
              totalRunSuccessfully + " khảo sát đã chạy thành công", totalPercentage))
          .newSurveysToday(newSurveysToday).completedSurveysThisWeek(completedThisWeek)
          .failedSurveysNeedReview(failedNeedReview)
          .totalSurveysRunSuccessfully(totalRunSuccessfully).build();

    } catch (Exception e) {
      log.error("Error calculating dashboard statistics: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to calculate dashboard statistics", e);
    }
  }

  @Override
  public SurveyStatisticsResponse getStatisticsByDateRange(String startDate, String endDate) {
    log.info("Calculating statistics for date range: {} to {}", startDate, endDate);

    try {
      DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
      LocalDateTime start = LocalDateTime.parse(startDate, formatter);
      LocalDateTime end = LocalDateTime.parse(endDate, formatter);

      Object[] stats = surveyStatisticsRepository.getStatisticsByDateRange(start, end);

      Long totalCount = (Long) stats[0];
      Long completedCount = (Long) stats[1];
      Long failedCount = (Long) stats[2];

      return calculateStatisticsFromCounts(totalCount, completedCount, failedCount);

    } catch (Exception e) {
      log.error("Error calculating statistics by date range: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to calculate statistics by date range", e);
    }
  }

  @Override
  public SurveyStatisticsResponse getStatisticsByFormId(String formId) {
    log.info("Calculating statistics for form ID: {}", formId);
    throw new UnsupportedOperationException("Form-specific statistics not yet implemented");
  }

  // Helper methods
  private LocalDateTime getStartOfWeek() {
    LocalDateTime now = LocalDateTime.now();
    return now.with(DayOfWeek.MONDAY).withHour(0).withMinute(0).withSecond(0).withNano(0);
  }

  private BigDecimal calculatePercentage(Long numerator, Long denominator) {
    if (denominator == null || denominator == 0) {
      return BigDecimal.ZERO;
    }
    return BigDecimal.valueOf(numerator)
        .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100));
  }

  private BigDecimal calculateTrendPercentage(Long current, Long previous) {
    if (previous == null || previous == 0) {
      return current > 0 ? BigDecimal.valueOf(100) : BigDecimal.ZERO;
    }

    BigDecimal currentBD = BigDecimal.valueOf(current);
    BigDecimal previousBD = BigDecimal.valueOf(previous);

    return currentBD.subtract(previousBD).divide(previousBD, 4, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100));
  }

  private String getTrendDirection(BigDecimal trend) {
    if (trend.compareTo(BigDecimal.ZERO) > 0) {
      return "up";
    } else if (trend.compareTo(BigDecimal.ZERO) < 0) {
      return "down";
    } else {
      return "stable";
    }
  }

  private SurveyStatisticsResponse.StatisticCard createStatisticCard(String title, String icon,
      String color, Long count, BigDecimal percentage, String trend, BigDecimal trendPercentage,
      String description, BigDecimal progressBarPercentage) {

    return SurveyStatisticsResponse.StatisticCard.builder().title(title).icon(icon).color(color)
        .count(count).percentage(percentage).trend(trend).trendPercentage(trendPercentage)
        .description(description).progressBarPercentage(progressBarPercentage).build();
  }

  private SurveyStatisticsResponse calculateStatisticsFromCounts(Long total, Long completed,
      Long failed) {
    Long pending = total - completed - failed;

    return SurveyStatisticsResponse.builder()
        .pendingSurveys(createStatisticCard("Khảo sát chờ xử lý", "clock", "#FFA500", pending,
            calculatePercentage(pending, total), "stable", BigDecimal.ZERO,
            pending + " khảo sát chờ xử lý", calculatePercentage(pending, total)))
        .successfulSurveys(createStatisticCard("Khảo sát thành công", "check", "#28a745", completed,
            calculatePercentage(completed, total), "stable", BigDecimal.ZERO,
            completed + " khảo sát hoàn thành", calculatePercentage(completed, total)))
        .failedSurveys(createStatisticCard("Khảo sát thất bại", "x", "#dc3545", failed,
            calculatePercentage(failed, total), "stable", BigDecimal.ZERO,
            failed + " khảo sát thất bại", calculatePercentage(failed, total)))
        .totalSurveys(createStatisticCard("Tổng số khảo sát", "file-text", "#6f42c1", total,
            BigDecimal.valueOf(100), "stable", BigDecimal.ZERO, total + " khảo sát tổng cộng",
            BigDecimal.valueOf(100)))
        .build();
  }
}
