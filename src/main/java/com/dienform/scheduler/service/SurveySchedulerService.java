package com.dienform.scheduler.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestRepository;
import com.dienform.tool.dienformtudong.fillschedule.entity.FillSchedule;
import com.dienform.tool.dienformtudong.form.entity.Form;
import com.dienform.tool.dienformtudong.form.repository.FormRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service responsible for scheduling and executing survey fill requests
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SurveySchedulerService {

  // private final FillScheduleRepository scheduleRepository;
  private final FillRequestRepository fillRequestRepository;
  private final FormRepository formRepository;
  // private final ExecutionService executionService;
  // private final GoogleFormService googleFormService;

  /**
   * Scheduled task that runs every hour to check for and execute scheduled survey fills
   */
  @Scheduled(cron = "0 0 * * * *") // Run every hour at the start of the hour
  @Transactional(readOnly = true)
  public void executeScheduledSurveys() {
    log.info("Starting scheduled survey execution check at {}", LocalDateTime.now());

    // Find active schedules that are currently valid (between start and end date)
    LocalDate today = LocalDate.now();
    List<FillSchedule> activeSchedules = new ArrayList<>();
    // List<FillSchedule> activeSchedules =
    // scheduleRepository.findByActiveIsTrueAndStartDateLessThanEqualAndEndDateGreaterThanEqual(today,
    // today);

    log.info("Found {} active schedules to process", activeSchedules.size());

    // Process each schedule asynchronously
    for (FillSchedule schedule : activeSchedules) {
      processSchedule(schedule);
    }
  }

  /**
   * Process a single schedule by executing the appropriate number of surveys
   * 
   * @param schedule The schedule to process
   */
  private void processSchedule(FillSchedule schedule) {
    UUID requestId = schedule.getFillRequest().getId();

    FillRequest request = fillRequestRepository.findById(requestId).orElse(null);
    if (request == null) {
      log.error("Could not find fill request with ID: {}", requestId);
      return;
    }

    Form form = formRepository.findById(null).orElse(null);
    if (form == null) {
      log.error("Could not find form with ID: {}", 1);
      return;
    }

    // Calculate how many executions to run in this cycle
    int executionsToRun = calculateExecutionsForCurrentHour(schedule);
    log.info("Will execute {} surveys for request ID: {}", executionsToRun, requestId);

    // For each execution, run it asynchronously
    for (int i = 0; i < executionsToRun; i++) {
      executeSurvey(request, form);
    }
  }

  /**
   * Calculate number of executions to run in the current hour based on distribution settings
   * 
   * @param schedule The schedule to calculate for
   * @return Number of executions to run
   */
  private int calculateExecutionsForCurrentHour(FillSchedule schedule) {
    // This is a simple implementation that evenly distributes executions throughout the day
    // For more complex distributions, the customSchedule JSON field can be used

    // return schedule.getExecutionsPerDay() / 24; // Distribute evenly throughout 24 hours
    return 0;
  }

  /**
   * Execute a single survey fill
   * 
   * @param request The fill request
   * @param form The form to fill
   */
  private void executeSurvey(FillRequest request, Form form) {
    // Execute the survey asynchronously
    CompletableFuture.runAsync(() -> {
      try {
        // Call Google Form service to fill the form
        // String responseData = googleFormService.fillForm(request, form);
        String responseData = "Simulated response data"; // Placeholder for actual response

        // Record successful execution
        // executionService.recordExecution(request.getId(), Constants.EXECUTION_STATUS_SUCCESS,
        // responseData, null);

        log.info("Successfully executed survey for request ID: {}", request.getId());
      } catch (Exception e) {
        // Record failed execution
        // executionService.recordExecution(request.getId(), Constants.EXECUTION_STATUS_FAILED,
        // null,
        // e.getMessage());

        log.error("Failed to execute survey for request ID: {}", request.getId(), e);
      }
    });
  }
}
