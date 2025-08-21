package com.dienform.scheduler.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Autowired;
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

  @Autowired
  private DataFillCampaignService dataFillCampaignService;

  @Autowired
  private ScheduleDistributionService scheduleDistributionService;

  @Autowired
  private com.dienform.realtime.FillRequestRealtimeGateway realtimeGateway;

  @Autowired
  private com.dienform.common.util.CurrentUserUtil currentUserUtil;

  /**
   * Scheduled task that checks for PENDING campaigns and starts them when scheduled Rate is
   * configurable via application-local.yml (campaign.scheduler.fixed-rate)
   */
  @Scheduled(fixedRateString = "#{@campaignSchedulerProperties.fixedRate}")
  @Transactional
  public void checkPendingCampaigns() {
    if (!schedulerConfig.isEnabled()) {
      return;
    }

    log.debug("Checking for PENDING campaigns to start at {}", LocalDateTime.now());

    // Find PENDING campaigns that should start now
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime checkTime = now.plusMinutes(1); // Include campaigns starting in next minute

    List<FillRequest> pendingCampaigns = fillRequestRepository
        .findByStatusAndStartDateLessThanEqual(FillRequestStatusEnum.PENDING, checkTime);

    if (pendingCampaigns.isEmpty()) {
      log.debug("No PENDING campaigns ready to start");
      return;
    }

    log.info("Found {} PENDING campaigns ready to start", pendingCampaigns.size());

    // Process each PENDING campaign
    for (FillRequest campaign : pendingCampaigns) {
      processPendingCampaign(campaign);
    }
  }

  /**
   * Legacy method - kept for compatibility Scheduled task that runs every hour to check for and
   * execute scheduled survey fills
   */
  // @Scheduled(cron = "0 0 * * * *") // Run every hour at the start of the hour
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

  @Scheduled(fixedRate = 300000) // Run every 5 minutes
  @Transactional
  public void checkStuckRunningCampaigns() {
    if (!schedulerConfig.isEnabled()) {
      return;
    }

    log.info("Checking for stuck RUNNING campaigns...");

    // Find campaigns that have been running for more than 30 minutes
    LocalDateTime thirtyMinutesAgo = LocalDateTime.now().minusMinutes(30);
    List<FillRequest> stuckCampaigns = fillRequestRepository.findByStatusAndStartDateLessThan(
        FillRequestStatusEnum.IN_PROCESS, thirtyMinutesAgo);

    if (stuckCampaigns.isEmpty()) {
      log.debug("No stuck RUNNING campaigns found");
      return;
    }

    log.warn("Found {} potentially stuck RUNNING campaigns", stuckCampaigns.size());

    // Process each stuck campaign
    for (FillRequest campaign : stuckCampaigns) {
      try {
        log.info("Marking stuck campaign as FAILED: {}", campaign.getId());
        campaign.setStatus(FillRequestStatusEnum.FAILED);
        fillRequestRepository.save(campaign);
      } catch (Exception e) {
        log.error("Error updating stuck campaign status: {}", campaign.getId(), e);
      }
    }
  }

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
   * Process a single PENDING campaign by starting it
   */
  private void processPendingCampaign(FillRequest campaign) {
    try {
      log.info("Starting PENDING campaign: {} scheduled for {}", campaign.getId(),
          campaign.getStartDate());

      // Update status to RUNNING
      campaign.setStatus(FillRequestStatusEnum.IN_PROCESS);
      fillRequestRepository.save(campaign);
      try {
        com.dienform.realtime.dto.FillRequestUpdateEvent evt =
            com.dienform.realtime.dto.FillRequestUpdateEvent.builder()
                .formId(campaign.getForm().getId().toString())
                .requestId(campaign.getId().toString())
                .status(FillRequestStatusEnum.IN_PROCESS.name())
                .completedSurvey(campaign.getCompletedSurvey())
                .surveyCount(campaign.getSurveyCount())
                .updatedAt(java.time.Instant.now().toString()).build();

        // Get current user ID if available
        String userId = null;
        try {
          userId = currentUserUtil.getCurrentUserIdIfPresent().map(UUID::toString).orElse(null);
        } catch (Exception ignore) {
          log.debug("Failed to get current user ID: {}", ignore.getMessage());
        }

        // Use centralized emit method with deduplication
        realtimeGateway.emitUpdateWithUser(campaign.getForm().getId().toString(), evt, userId);
      } catch (Exception ignore) {
        log.debug("Failed to emit IN_PROCESS update: {}", ignore.getMessage());
      }

      // Get form and questions
      Form form = campaign.getForm();
      if (form == null) {
        log.error("Form not found for campaign: {}", campaign.getId());
        campaign.setStatus(FillRequestStatusEnum.FAILED);
        fillRequestRepository.save(campaign);
        try {
          com.dienform.realtime.dto.FillRequestUpdateEvent evt =
              com.dienform.realtime.dto.FillRequestUpdateEvent.builder()
                  .formId(campaign.getForm().getId().toString())
                  .requestId(campaign.getId().toString())
                  .status(FillRequestStatusEnum.FAILED.name())
                  .completedSurvey(campaign.getCompletedSurvey())
                  .surveyCount(campaign.getSurveyCount())
                  .updatedAt(java.time.Instant.now().toString()).build();
          realtimeGateway.emitUpdate(campaign.getForm().getId().toString(), evt);
          try {
            currentUserUtil.getCurrentUserIdIfPresent().ifPresent(uid -> {
              realtimeGateway.emitBulkStateForUser(uid.toString(),
                  campaign.getForm().getId().toString());
              realtimeGateway.emitUpdateForUser(uid.toString(),
                  campaign.getForm().getId().toString(), evt);
            });
          } catch (Exception ignore) {
          }
        } catch (Exception ignore) {
        }
        return;
      }

      List<Question> questions = questionRepository.findByFormIdOrderByPosition(form.getId());
      if (questions.isEmpty()) {
        log.error("No questions found for form: {}", form.getId());
        campaign.setStatus(FillRequestStatusEnum.FAILED);
        fillRequestRepository.save(campaign);
        try {
          com.dienform.realtime.dto.FillRequestUpdateEvent evt =
              com.dienform.realtime.dto.FillRequestUpdateEvent.builder()
                  .formId(campaign.getForm().getId().toString())
                  .requestId(campaign.getId().toString())
                  .status(FillRequestStatusEnum.FAILED.name())
                  .completedSurvey(campaign.getCompletedSurvey())
                  .surveyCount(campaign.getSurveyCount())
                  .updatedAt(java.time.Instant.now().toString()).build();
          realtimeGateway.emitUpdate(campaign.getForm().getId().toString(), evt);
          try {
            currentUserUtil.getCurrentUserIdIfPresent().ifPresent(uid -> {
              realtimeGateway.emitBulkStateForUser(uid.toString(),
                  campaign.getForm().getId().toString());
              realtimeGateway.emitUpdateForUser(uid.toString(),
                  campaign.getForm().getId().toString(), evt);
            });
          } catch (Exception ignore) {
          }
        } catch (Exception ignore) {
        }
        return;
      }

      // Get stored mappings
      List<FillRequestMapping> storedMappings =
          fillRequestMappingRepository.findByFillRequestId(campaign.getId());
      if (storedMappings.isEmpty()) {
        log.error("No mappings found for campaign: {}", campaign.getId());
        campaign.setStatus(FillRequestStatusEnum.FAILED);
        fillRequestRepository.save(campaign);
        try {
          com.dienform.realtime.dto.FillRequestUpdateEvent evt =
              com.dienform.realtime.dto.FillRequestUpdateEvent.builder()
                  .formId(campaign.getForm().getId().toString())
                  .requestId(campaign.getId().toString())
                  .status(FillRequestStatusEnum.FAILED.name())
                  .completedSurvey(campaign.getCompletedSurvey())
                  .surveyCount(campaign.getSurveyCount())
                  .updatedAt(java.time.Instant.now().toString()).build();
          realtimeGateway.emitUpdate(campaign.getForm().getId().toString(), evt);
        } catch (Exception ignore) {
        }
        return;
      }

      // Reconstruct DataFillRequestDTO from stored data
      DataFillRequestDTO reconstructedRequest =
          reconstructDataFillRequest(campaign, storedMappings);

      // Create schedule distribution
      List<ScheduleDistributionService.ScheduledTask> schedule =
          scheduleDistributionService.distributeSchedule(campaign.getSurveyCount(),
              campaign.getStartDate(), campaign.getEndDate(), campaign.isHumanLike());

      // Execute campaign - status will be updated by GoogleFormService after actual form filling
      dataFillCampaignService.executeCampaign(campaign, reconstructedRequest, questions, schedule)
          .exceptionally(throwable -> {
            log.error("Campaign {} execution failed", campaign.getId(), throwable);
            campaign.setStatus(FillRequestStatusEnum.FAILED);
            fillRequestRepository.save(campaign);
            try {
              com.dienform.realtime.dto.FillRequestUpdateEvent evt =
                  com.dienform.realtime.dto.FillRequestUpdateEvent.builder()
                      .formId(campaign.getForm().getId().toString())
                      .requestId(campaign.getId().toString())
                      .status(FillRequestStatusEnum.FAILED.name())
                      .completedSurvey(campaign.getCompletedSurvey())
                      .surveyCount(campaign.getSurveyCount())
                      .updatedAt(java.time.Instant.now().toString()).build();

              // Get current user ID if available
              String userId = null;
              try {
                userId =
                    currentUserUtil.getCurrentUserIdIfPresent().map(UUID::toString).orElse(null);
              } catch (Exception ignore) {
                log.debug("Failed to get current user ID: {}", ignore.getMessage());
              }

              // Use centralized emit method with deduplication
              realtimeGateway.emitUpdateWithUser(campaign.getForm().getId().toString(), evt,
                  userId);
            } catch (Exception ignore) {
              log.debug("Failed to emit FAILED update: {}", ignore.getMessage());
            }
            return null;
          });

      log.info("Campaign {} status updated to RUNNING with {} scheduled tasks", campaign.getId(),
          schedule.size());

    } catch (Exception e) {
      log.error("Failed to start campaign: {}", campaign.getId(), e);

      // Update status to FAILED
      campaign.setStatus(FillRequestStatusEnum.FAILED);
      fillRequestRepository.save(campaign);
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
}
