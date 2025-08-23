package com.dienform.scheduler.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.config.CampaignSchedulerConfig.CampaignSchedulerProperties;
import com.dienform.tool.dienformtudong.datamapping.dto.request.ColumnMapping;
import com.dienform.tool.dienformtudong.datamapping.dto.request.DataFillRequestDTO;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequestMapping;
import com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestMappingRepository;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestRepository;
import com.dienform.tool.dienformtudong.fillrequest.service.DataFillCampaignService;
import com.dienform.tool.dienformtudong.fillrequest.service.QueueManagementService;
import com.dienform.tool.dienformtudong.fillrequest.service.ScheduleDistributionService;
import com.dienform.tool.dienformtudong.fillschedule.entity.FillSchedule;
import com.dienform.tool.dienformtudong.form.entity.Form;
import com.dienform.tool.dienformtudong.form.repository.FormRepository;
import com.dienform.tool.dienformtudong.googleform.service.impl.GoogleFormServiceImpl;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.repository.QuestionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service responsible for scheduling and executing survey fill requests Checks for PENDING
 * campaigns and starts them when their scheduled time arrives
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "campaign.scheduler", name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class SurveySchedulerService {

  private final FillRequestRepository fillRequestRepository;
  private final FillRequestMappingRepository fillRequestMappingRepository;
  private final FormRepository formRepository;
  private final QuestionRepository questionRepository;
  private final CampaignSchedulerProperties schedulerConfig;
  private final GoogleFormServiceImpl googleFormServiceImpl;
  private final DataFillCampaignService dataFillCampaignService;
  private final ScheduleDistributionService scheduleDistributionService;
  private final com.dienform.realtime.FillRequestRealtimeGateway realtimeGateway;
  private final com.dienform.common.util.CurrentUserUtil currentUserUtil;
  private final QueueManagementService queueManagementService;

  /**
   * Scheduled task that checks for QUEUED campaigns and starts them when scheduled Rate is
   * configurable via application properties
   */
  @Scheduled(fixedRateString = "${campaign.scheduler.fixed-rate:30000}")
  @Transactional
  public void checkQueuedCampaigns() {
    if (!schedulerConfig.isEnabled()) {
      return;
    }

    log.debug("Checking for QUEUED campaigns to start at {}", LocalDateTime.now());

    // Find QUEUED campaigns that should start now
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime checkTime = now.plusMinutes(1); // Include campaigns starting in next minute

    List<FillRequest> queuedCampaigns = fillRequestRepository
        .findByStatusAndStartDateLessThanEqual(FillRequestStatusEnum.QUEUED, checkTime);

    if (queuedCampaigns.isEmpty()) {
      log.debug("No QUEUED campaigns ready to start");
      return;
    }

    log.info("Found {} QUEUED campaigns ready to start", queuedCampaigns.size());

    // Process each QUEUED campaign
    for (FillRequest campaign : queuedCampaigns) {
      processQueuedCampaign(campaign);
    }
  }

  /**
   * Scheduled task that processes QUEUED requests when there's capacity Runs more frequently to
   * handle immediate processing
   */
  @Scheduled(fixedRate = 10000) // 10 seconds
  @Transactional
  public void processQueuedRequests() {
    if (!schedulerConfig.isEnabled()) {
      return;
    }

    log.debug("Checking for QUEUED requests to process at {}", LocalDateTime.now());

    // Check if queue management service has capacity
    if (!queueManagementService.hasAvailableCapacity()) {
      log.debug("No capacity available for processing QUEUED requests");
      return;
    }

    // Find QUEUED requests that can be processed immediately
    List<FillRequest> queuedRequests = fillRequestRepository
        .findByStatusAndStartDateLessThanEqual(FillRequestStatusEnum.QUEUED, LocalDateTime.now());

    if (queuedRequests.isEmpty()) {
      log.debug("No QUEUED requests to process");
      return;
    }

    log.info("Found {} QUEUED requests to process", queuedRequests.size());

    // Process QUEUED requests based on priority and queue position
    List<FillRequest> sortedRequests = queuedRequests.stream().sorted((a, b) -> {
      // Sort by priority (higher first), then by queue position (lower first)
      int priorityCompare = Integer.compare(b.getPriority(), a.getPriority());
      if (priorityCompare != 0) {
        return priorityCompare;
      }
      return Integer.compare(
          a.getQueuePosition() != null ? a.getQueuePosition() : Integer.MAX_VALUE,
          b.getQueuePosition() != null ? b.getQueuePosition() : Integer.MAX_VALUE);
    }).collect(Collectors.toList());

    // Process up to available capacity
    int availableSlots =
        queueManagementService.getThreadPoolSize() - queueManagementService.getActiveCount();
    int processed = 0;

    for (FillRequest request : sortedRequests) {
      if (processed >= availableSlots) {
        break;
      }

      if (processQueuedRequest(request)) {
        processed++;
        log.info("Started processing QUEUED request: {} ({}/{})", request.getId(), processed,
            availableSlots);
      }
    }

    if (processed > 0) {
      log.info("Started processing {} QUEUED requests", processed);
    }
  }

  /**
   * Recovery task to handle requests that were interrupted during deployment Runs every 5 minutes
   * to check for stuck requests
   */
  @Scheduled(fixedRate = 300000) // 5 minutes
  @Transactional
  public void recoverInterruptedRequests() {
    if (!schedulerConfig.isEnabled()) {
      return;
    }

    log.debug("Checking for interrupted requests to recover at {}", LocalDateTime.now());

    // Find requests that were IN_PROCESS for too long (likely interrupted during deployment)
    LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(30); // 30 minutes ago

    List<FillRequest> interruptedRequests = fillRequestRepository
        .findByStatusAndUpdatedAtBefore(FillRequestStatusEnum.IN_PROCESS, cutoffTime);

    if (interruptedRequests.isEmpty()) {
      log.debug("No interrupted requests found");
      return;
    }

    log.info("Found {} interrupted requests to recover", interruptedRequests.size());

    for (FillRequest request : interruptedRequests) {
      try {
        log.info("Recovering interrupted request: {} (completed: {}/{})", request.getId(),
            request.getCompletedSurvey(), request.getSurveyCount());

        // Check if the request is already completed
        if (request.getCompletedSurvey() >= request.getSurveyCount()) {
          log.info("Request {} is already completed ({}), updating status to COMPLETED",
              request.getId(), request.getCompletedSurvey());
          request.setStatus(FillRequestStatusEnum.COMPLETED);
          fillRequestRepository.save(request);

          // Emit completion update
          emitRecoveryUpdate(request, FillRequestStatusEnum.COMPLETED);
          continue;
        }

        // Calculate remaining surveys to complete
        int remainingSurveys = request.getSurveyCount() - request.getCompletedSurvey();
        log.info("Request {} needs {} more surveys to complete", request.getId(), remainingSurveys);

        // Reset to QUEUED status so it can be processed again
        request.setStatus(FillRequestStatusEnum.QUEUED);
        fillRequestRepository.save(request);

        // Start processing the remaining surveys
        startRecoveryProcessing(request, remainingSurveys);

        log.info("Successfully recovered request: {} with {} remaining surveys", request.getId(),
            remainingSurveys);

      } catch (Exception e) {
        log.error("Failed to recover request: {}", request.getId(), e);
      }
    }
  }

  /**
   * Legacy method - kept for compatibility Scheduled task that runs every hour to check for and
   * execute scheduled survey fills
   */
  // @Scheduled(cron = "0 0 * * * *") // Run every hour at the start of the hour
  // @Transactional(readOnly = true)
  // public void executeScheduledSurveys() {
  // log.info("Starting scheduled survey execution check at {}", LocalDateTime.now());

  // // Find active schedules that are currently valid (between start and end date)
  // LocalDate today = LocalDate.now();
  // List<FillSchedule> activeSchedules = new ArrayList<>();
  // // List<FillSchedule> activeSchedules =
  // //
  // scheduleRepository.findByActiveIsTrueAndStartDateLessThanEqualAndEndDateGreaterThanEqual(today,
  // // today);

  // log.info("Found {} active schedules to process", activeSchedules.size());

  // // Process each schedule asynchronously
  // for (FillSchedule schedule : activeSchedules) {
  // processSchedule(schedule);
  // }
  // }

  // // @Scheduled(fixedRate = 300000) // Run every 5 minutes
  // @Transactional
  // public void checkStuckRunningCampaigns() {
  // if (!schedulerConfig.isEnabled()) {
  // return;
  // }

  // log.info("Checking for stuck RUNNING campaigns...");

  // // Find campaigns that have been running for more than 30 minutes
  // LocalDateTime thirtyMinutesAgo = LocalDateTime.now().minusMinutes(30);
  // List<FillRequest> stuckCampaigns = fillRequestRepository
  // .findByStatusAndStartDateLessThan(FillRequestStatusEnum.IN_PROCESS, thirtyMinutesAgo);

  // if (stuckCampaigns.isEmpty()) {
  // log.debug("No stuck RUNNING campaigns found");
  // return;
  // }

  // log.warn("Found {} potentially stuck RUNNING campaigns", stuckCampaigns.size());

  // Process each stuck campaign
  // for (FillRequest campaign : stuckCampaigns) {
  // try {
  // log.info("Marking stuck campaign as FAILED: {}", campaign.getId());
  // campaign.setStatus(FillRequestStatusEnum.FAILED);
  // fillRequestRepository.save(campaign);
  // } catch (Exception e) {
  // log.error("Error updating stuck campaign status: {}", campaign.getId(), e);
  // }
  // }
  // }

  /**
   * TTL scheduler to clear in-memory caches periodically to prevent growth Runs every 15 minutes
   */
  @Scheduled(fixedRate = 15 * 60 * 1000)
  public void clearCacheTtl() {
    try {
      log.info("TTL cache cleanup triggered (every 15 minutes)");
      googleFormServiceImpl.clearCaches();
    } catch (Exception e) {
      log.error("Error during TTL cache cleanup", e);
    }
  }

  /**
   * Process a single QUEUED campaign by starting it
   */
  private void processQueuedCampaign(FillRequest campaign) {
    try {
      log.info("Starting QUEUED campaign: {} scheduled for {}", campaign.getId(),
          campaign.getStartDate());

      // Check if we have capacity to process this campaign
      if (!queueManagementService.hasAvailableCapacity()) {
        log.info("No capacity available for campaign: {}, keeping in queue", campaign.getId());
        return;
      }

      // Start the campaign
      if (processQueuedRequest(campaign)) {
        log.info("Successfully started campaign: {}", campaign.getId());
      } else {
        log.warn("Failed to start campaign: {}", campaign.getId());
      }

    } catch (Exception e) {
      log.error("Error starting campaign: {}", campaign.getId(), e);
    }
  }

  /**
   * Process a single QUEUED request
   */
  private boolean processQueuedRequest(FillRequest request) {
    try {
      log.info("Processing QUEUED request: {} (priority: {}, position: {})", request.getId(),
          request.getPriority(), request.getQueuePosition());

      // Check if this is a data fill request
      List<FillRequestMapping> mappings =
          fillRequestMappingRepository.findByFillRequestId(request.getId());

      if (!mappings.isEmpty()) {
        // This is a data fill request
        return processDataFillRequest(request, mappings);
      } else {
        // This is a regular fill request
        return processRegularFillRequest(request);
      }

    } catch (Exception e) {
      log.error("Error processing QUEUED request: {}", request.getId(), e);
      return false;
    }
  }

  /**
   * Process data fill request
   */
  private boolean processDataFillRequest(FillRequest request, List<FillRequestMapping> mappings) {
    try {
      log.info("Processing data fill request: {}", request.getId());

      // Get form and questions
      Form form = formRepository.findById(request.getForm().getId())
          .orElseThrow(() -> new RuntimeException("Form not found"));
      List<Question> questions = questionRepository.findByForm(form);

      // Reconstruct DataFillRequestDTO
      DataFillRequestDTO reconstructedRequest = reconstructDataFillRequest(request, mappings);

      // Create schedule distribution
      List<ScheduleDistributionService.ScheduledTask> schedule =
          scheduleDistributionService.distributeSchedule(request.getSurveyCount(),
              request.getStartDate(), request.getEndDate(), request.isHumanLike());

      // Execute campaign
      dataFillCampaignService.executeCampaign(request, reconstructedRequest, questions, schedule)
          .exceptionally(throwable -> {
            log.error("Campaign execution failed for request: {}", request.getId(), throwable);
            return null;
          });

      return true;

    } catch (Exception e) {
      log.error("Failed to process data fill request: {}", request.getId(), e);
      return false;
    }
  }

  /**
   * Process regular fill request
   */
  private boolean processRegularFillRequest(FillRequest request) {
    try {
      log.info("Processing regular fill request: {}", request.getId());

      // Use GoogleFormService to process
      googleFormServiceImpl.fillForm(request.getId());
      return true;

    } catch (Exception e) {
      log.error("Failed to process regular fill request: {}", request.getId(), e);
      return false;
    }
  }

  /**
   * Reconstruct DataFillRequestDTO from stored FillRequest and mappings
   */
  private DataFillRequestDTO reconstructDataFillRequest(FillRequest fillRequest,
      List<FillRequestMapping> mappings) {
    DataFillRequestDTO dto = new DataFillRequestDTO();
    dto.setFormName(fillRequest.getForm().getName());
    dto.setFormId(fillRequest.getForm().getId().toString());
    dto.setSheetLink(mappings.get(0).getSheetLink()); // All mappings have same sheet link
    dto.setSubmissionCount(fillRequest.getSurveyCount());
    dto.setPricePerSurvey(fillRequest.getPricePerSurvey());
    dto.setIsHumanLike(fillRequest.isHumanLike());
    dto.setStartDate(fillRequest.getStartDate());
    dto.setEndDate(fillRequest.getEndDate());

    // Convert mappings
    List<ColumnMapping> questionMappings = new ArrayList<>();
    for (FillRequestMapping mapping : mappings) {
      ColumnMapping columnMapping = new ColumnMapping();
      columnMapping.setQuestionId(mapping.getQuestionId().toString());
      columnMapping.setColumnName(mapping.getColumnName());
      questionMappings.add(columnMapping);
    }
    dto.setMappings(questionMappings);

    return dto;
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

  /**
   * Start processing remaining surveys for a recovered request
   */
  private void startRecoveryProcessing(FillRequest fillRequest, int remainingSurveys) {
    try {
      log.info("Starting recovery processing for request: {} with {} remaining surveys",
          fillRequest.getId(), remainingSurveys);

      // Get form and questions
      Form form = fillRequest.getForm();
      if (form == null) {
        log.error("Form not found for recovered request: {}", fillRequest.getId());
        fillRequest.setStatus(FillRequestStatusEnum.FAILED);
        fillRequestRepository.save(fillRequest);
        return;
      }

      List<Question> questions = questionRepository.findByForm(form);
      if (questions.isEmpty()) {
        log.error("No questions found for form: {}", form.getId());
        fillRequest.setStatus(FillRequestStatusEnum.FAILED);
        fillRequestRepository.save(fillRequest);
        return;
      }

      // Check if this is a data fill request
      List<FillRequestMapping> mappings =
          fillRequestMappingRepository.findByFillRequestId(fillRequest.getId());

      if (!mappings.isEmpty()) {
        // This is a data fill request - recover using data fill campaign
        recoverDataFillRequest(fillRequest, questions, mappings, remainingSurveys);
      } else {
        // This is a regular fill request - recover using GoogleFormService
        recoverRegularFillRequest(fillRequest, remainingSurveys);
      }

    } catch (Exception e) {
      log.error("Failed to start recovery processing for request: {}", fillRequest.getId(), e);
      fillRequest.setStatus(FillRequestStatusEnum.FAILED);
      fillRequestRepository.save(fillRequest);
    }
  }

  /**
   * Recover data fill request with remaining surveys
   */
  private void recoverDataFillRequest(FillRequest fillRequest, List<Question> questions,
      List<FillRequestMapping> mappings, int remainingSurveys) {
    try {
      log.info("Recovering data fill request: {} with {} remaining surveys", fillRequest.getId(),
          remainingSurveys);

      // Reconstruct DataFillRequestDTO
      DataFillRequestDTO reconstructedRequest = reconstructDataFillRequest(fillRequest, mappings);

      // Create schedule distribution for remaining surveys only
      List<ScheduleDistributionService.ScheduledTask> schedule =
          scheduleDistributionService.distributeSchedule(remainingSurveys, LocalDateTime.now(),
              fillRequest.getEndDate(), fillRequest.isHumanLike());

      // Execute campaign for remaining surveys
      dataFillCampaignService
          .executeCampaign(fillRequest, reconstructedRequest, questions, schedule)
          .exceptionally(throwable -> {
            log.error("Recovery campaign execution failed for request: {}", fillRequest.getId(),
                throwable);
            fillRequest.setStatus(FillRequestStatusEnum.FAILED);
            fillRequestRepository.save(fillRequest);
            return null;
          });

    } catch (Exception e) {
      log.error("Failed to recover data fill request: {}", fillRequest.getId(), e);
      fillRequest.setStatus(FillRequestStatusEnum.FAILED);
      fillRequestRepository.save(fillRequest);
    }
  }

  /**
   * Recover regular fill request with remaining surveys
   */
  private void recoverRegularFillRequest(FillRequest fillRequest, int remainingSurveys) {
    try {
      log.info("Recovering regular fill request: {} with {} remaining surveys", fillRequest.getId(),
          remainingSurveys);

      // Use GoogleFormService to process remaining surveys
      // The service will handle the remaining count internally
      googleFormServiceImpl.fillForm(fillRequest.getId());

    } catch (Exception e) {
      log.error("Failed to recover regular fill request: {}", fillRequest.getId(), e);
      fillRequest.setStatus(FillRequestStatusEnum.FAILED);
      fillRequestRepository.save(fillRequest);
    }
  }

  /**
   * Emit recovery update to realtime gateway
   */
  private void emitRecoveryUpdate(FillRequest fillRequest, FillRequestStatusEnum status) {
    try {
      com.dienform.realtime.dto.FillRequestUpdateEvent evt =
          com.dienform.realtime.dto.FillRequestUpdateEvent.builder()
              .formId(fillRequest.getForm().getId().toString())
              .requestId(fillRequest.getId().toString()).status(status.name())
              .completedSurvey(fillRequest.getCompletedSurvey())
              .surveyCount(fillRequest.getSurveyCount())
              .updatedAt(java.time.Instant.now().toString()).build();

      // Get current user ID if available
      String userId = null;
      try {
        userId = currentUserUtil.getCurrentUserIdIfPresent().map(UUID::toString).orElse(null);
      } catch (Exception ignore) {
        log.debug("Failed to get current user ID: {}", ignore.getMessage());
      }

      // Use centralized emit method with deduplication
      realtimeGateway.emitUpdateWithUser(fillRequest.getForm().getId().toString(), evt, userId);
    } catch (Exception e) {
      log.debug("Failed to emit recovery update: {}", e.getMessage());
    }
  }
}
