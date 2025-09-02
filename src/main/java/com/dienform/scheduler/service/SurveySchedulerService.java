package com.dienform.scheduler.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.dienform.common.util.DateTimeUtil;
import com.dienform.config.CampaignSchedulerConfig.CampaignSchedulerProperties;
import com.dienform.tool.dienformtudong.answerdistribution.entity.AnswerDistribution;
import com.dienform.tool.dienformtudong.answerdistribution.repository.AnswerDistributionRepository;
import com.dienform.tool.dienformtudong.datamapping.dto.request.ColumnMapping;
import com.dienform.tool.dienformtudong.datamapping.dto.request.DataFillRequestDTO;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequestMapping;
import com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestMappingRepository;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestRepository;
import com.dienform.tool.dienformtudong.fillrequest.service.BatchDataFillCampaignService;
import com.dienform.tool.dienformtudong.fillrequest.service.DataFillCampaignService;
import com.dienform.tool.dienformtudong.fillrequest.service.QueueManagementService;
import com.dienform.tool.dienformtudong.form.entity.Form;
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
  private final AnswerDistributionRepository answerDistributionRepository;
  private final QuestionRepository questionRepository;
  private final CampaignSchedulerProperties schedulerConfig;
  private final GoogleFormServiceImpl googleFormServiceImpl;
  private final DataFillCampaignService dataFillCampaignService;
  private final BatchDataFillCampaignService batchDataFillCampaignService;
  private final QueueManagementService queueManagementService;
  private final com.dienform.tool.dienformtudong.fillrequest.service.FillRequestCounterService fillRequestCounterService;

  /**
   * Scheduled task that checks for QUEUED campaigns and starts them when scheduled Rate is
   * configurable via application properties
   */
  @Scheduled(fixedRateString = "${campaign.scheduler.fixed-rate:30000}")
  public void checkQueuedCampaigns() {
    if (!schedulerConfig.isEnabled()) {
      return;
    }

    log.debug("Checking for QUEUED campaigns to start at {}",
        DateTimeUtil.formatForLog(DateTimeUtil.now()));

    // Find QUEUED campaigns that should start now
    LocalDateTime now = DateTimeUtil.now();
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
  @Scheduled(fixedRate = 30000) // 30 seconds - increased for better coordination
  public void processQueuedRequests() {
    if (!schedulerConfig.isEnabled()) {
      return;
    }

    log.debug("SurveySchedulerService: Checking for QUEUED requests to process at {}",
        DateTimeUtil.formatForLog(DateTimeUtil.now()));

    // Check if queue management service has capacity
    if (!queueManagementService.hasAvailableCapacity()) {
      log.debug("SurveySchedulerService: No capacity available for processing QUEUED requests");
      return;
    }

    // Find QUEUED requests that can be processed immediately
    LocalDateTime now = DateTimeUtil.now();
    List<FillRequest> queuedRequests = fillRequestRepository
        .findByStatusAndStartDateLessThanEqual(FillRequestStatusEnum.QUEUED, now);

    if (queuedRequests.isEmpty()) {
      log.debug("SurveySchedulerService: No QUEUED requests to process");
      return;
    }

    log.info("SurveySchedulerService: Found {} QUEUED requests to process", queuedRequests.size());

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
        log.debug("SurveySchedulerService: Reached capacity limit ({} slots), stopping processing",
            availableSlots);
        break;
      }

      // Try to atomically take the request: QUEUED -> IN_PROCESS
      boolean taken = fillRequestCounterService.compareAndSetStatus(request.getId(),
          FillRequestStatusEnum.QUEUED, FillRequestStatusEnum.IN_PROCESS);
      if (!taken) {
        log.debug("Request {} already taken by another worker", request.getId());
        continue;
      }

      if (processQueuedRequest(request)) {
        processed++;
        log.info("SurveySchedulerService: Started processing QUEUED request: {} ({}/{})",
            request.getId(), processed, availableSlots);
      }
    }

    if (processed > 0) {
      log.info("SurveySchedulerService: Successfully started processing {} QUEUED requests",
          processed);
    } else {
      log.debug("SurveySchedulerService: No requests were processed (capacity: {}, available: {})",
          queueManagementService.getThreadPoolSize(), availableSlots);
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

      // Atomically take the campaign: QUEUED -> IN_PROCESS
      boolean taken = fillRequestCounterService.compareAndSetStatus(campaign.getId(),
          FillRequestStatusEnum.QUEUED, FillRequestStatusEnum.IN_PROCESS);
      if (!taken) {
        log.info("Campaign {} already taken by another worker", campaign.getId());
        return;
      }

      // Start the campaign (already IN_PROCESS)
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
      log.info("Processing data fill request: {} with {} mappings", request.getId(),
          mappings.size());

      // Get form and questions
      Form form = request.getForm();
      if (form == null) {
        log.error("Form not found for request: {}", request.getId());
        return false;
      }

      List<Question> questions = questionRepository.findByForm(form);
      if (questions.isEmpty()) {
        log.error("No questions found for form: {}", form.getId());
        return false;
      }

      // Convert mappings to DataFillRequestDTO
      DataFillRequestDTO dataFillRequest = convertToDataFillRequest(request, mappings);

      // Execute campaign using batch processing
      batchDataFillCampaignService.executeBatchCampaign(request, dataFillRequest, questions)
          .exceptionally(throwable -> {
            log.error("Batch campaign execution failed for request: {}", request.getId(),
                throwable);
            return null;
          });

      return true;

    } catch (Exception e) {
      log.error("Error processing data fill request: {}", request.getId(), e);
      return false;
    }
  }

  /**
   * Process regular fill request with same logic as data fill request
   */
  private boolean processRegularFillRequest(FillRequest request) {
    try {
      log.info("Processing regular fill request: {}", request.getId());

      // Get form and questions
      Form form = request.getForm();
      List<Question> questions = questionRepository.findByForm(form);
      if (questions.isEmpty()) {
        log.error("No questions found for form: {}", form.getId());
        return false;
      }

      // Convert regular fill request to DataFillRequestDTO for consistent processing
      DataFillRequestDTO dataFillRequest = convertRegularToDataFillRequest(request);

      // Execute campaign using DataFillCampaignService with adaptive timeout and circuit breaker
      dataFillCampaignService.executeRegularCampaign(request, questions)
          .exceptionally(throwable -> {
            log.error("Regular campaign execution failed for request: {}", request.getId(),
                throwable);
            return null;
          });

      return true;

    } catch (Exception e) {
      log.error("Error processing regular fill request: {}", request.getId(), e);
      return false;
    }
  }

  /**
   * Convert FillRequestMapping to DataFillRequestDTO
   */
  private DataFillRequestDTO convertToDataFillRequest(FillRequest request,
      List<FillRequestMapping> mappings) {
    List<ColumnMapping> columnMappings = mappings.stream().map(mapping -> {
      ColumnMapping columnMapping = new ColumnMapping();
      columnMapping.setQuestionId(mapping.getQuestionId().toString());
      columnMapping.setColumnName(mapping.getColumnName());
      return columnMapping;
    }).collect(Collectors.toList());

    DataFillRequestDTO dataFillRequest = new DataFillRequestDTO();
    dataFillRequest.setFormId(request.getForm().getId().toString());
    dataFillRequest.setSubmissionCount(request.getSurveyCount());
    dataFillRequest.setStartDate(request.getStartDate());
    dataFillRequest.setEndDate(request.getEndDate());
    dataFillRequest.setIsHumanLike(request.isHumanLike());
    dataFillRequest.setMappings(columnMappings);
    dataFillRequest.setSheetLink(mappings.get(0).getSheetLink()); // All mappings have same sheet
                                                                  // link
    dataFillRequest.setFormName(request.getForm().getName());
    dataFillRequest.setPricePerSurvey(request.getPricePerSurvey());

    return dataFillRequest;
  }

  /**
   * Convert regular fill request to DataFillRequestDTO for consistent processing
   */
  private DataFillRequestDTO convertRegularToDataFillRequest(FillRequest request) {
    // For regular fill requests, we need to create mappings based on answer distributions
    List<ColumnMapping> columnMappings = new ArrayList<>();

    // Get answer distributions for this request
    List<AnswerDistribution> distributions =
        answerDistributionRepository.findByFillRequestId(request.getId());

    // Create mappings based on answer distributions
    for (AnswerDistribution distribution : distributions) {
      ColumnMapping columnMapping = new ColumnMapping();
      columnMapping.setQuestionId(distribution.getQuestion().getId().toString());

      // For regular fill requests, we use a special column name format
      // that indicates this is a distribution-based mapping
      columnMapping.setColumnName("DISTRIBUTION_" + distribution.getQuestion().getId());

      columnMappings.add(columnMapping);
    }

    DataFillRequestDTO dataFillRequest = new DataFillRequestDTO();
    dataFillRequest.setFormId(request.getForm().getId().toString());
    dataFillRequest.setSubmissionCount(request.getSurveyCount());
    dataFillRequest.setStartDate(request.getStartDate());
    dataFillRequest.setEndDate(request.getEndDate());
    dataFillRequest.setIsHumanLike(request.isHumanLike());
    dataFillRequest.setMappings(columnMappings);
    dataFillRequest.setSheetLink("REGULAR_FILL_REQUEST"); // Special identifier for regular requests
    dataFillRequest.setFormName(request.getForm().getName());
    dataFillRequest.setPricePerSurvey(request.getPricePerSurvey());

    return dataFillRequest;
  }

  /**
   * Reconstruct DataFillRequestDTO from existing mappings
   */
  private DataFillRequestDTO reconstructDataFillRequest(FillRequest request,
      List<FillRequestMapping> mappings) {
    List<ColumnMapping> columnMappings = mappings.stream().map(mapping -> {
      ColumnMapping columnMapping = new ColumnMapping();
      columnMapping.setQuestionId(mapping.getQuestionId().toString());
      columnMapping.setColumnName(mapping.getColumnName());
      return columnMapping;
    }).collect(Collectors.toList());

    DataFillRequestDTO dataFillRequest = new DataFillRequestDTO();
    dataFillRequest.setFormId(request.getForm().getId().toString());
    dataFillRequest.setSubmissionCount(request.getSurveyCount());
    dataFillRequest.setStartDate(request.getStartDate());
    dataFillRequest.setEndDate(request.getEndDate());
    dataFillRequest.setIsHumanLike(request.isHumanLike());
    dataFillRequest.setMappings(columnMappings);
    dataFillRequest.setSheetLink(mappings.get(0).getSheetLink()); // All mappings have same sheet
                                                                  // link
    dataFillRequest.setFormName(request.getForm().getName());
    dataFillRequest.setPricePerSurvey(request.getPricePerSurvey());

    return dataFillRequest;
  }

  /**
   * Execute a single survey for a request
   */
  private void executeSurvey(FillRequest request) {
    CompletableFuture.runAsync(() -> {
      try {
        log.info("Executing survey for request ID: {}", request.getId());

        // Use GoogleFormService to execute the survey
        googleFormServiceImpl.fillForm(request.getId());

        log.info("Successfully executed survey for request ID: {}", request.getId());
      } catch (Exception e) {
        log.error("Failed to execute survey for request ID: {}", request.getId(), e);
      }
    });
  }
}
