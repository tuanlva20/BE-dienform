package com.dienform.tool.dienformtudong.fillrequest.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.common.util.CurrentUserUtil;
import com.dienform.common.util.DateTimeUtil;
import com.dienform.realtime.FillRequestRealtimeGateway;
import com.dienform.tool.dienformtudong.datamapping.dto.request.DataFillRequestDTO;
import com.dienform.tool.dienformtudong.datamapping.service.GoogleSheetsService;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequestMapping;
import com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestMappingRepository;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestRepository;
import com.dienform.tool.dienformtudong.form.entity.Form;
import com.dienform.tool.dienformtudong.form.repository.FormRepository;
import com.dienform.tool.dienformtudong.googleform.service.GoogleFormService;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.repository.QuestionRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class QueueManagementService {

  /**
   * Queue status information
   */
  public static class QueueStatus {
    public static class Builder {
      private long queuedCount;
      private int activeCount;
      private int maxQueueSize;
      private int threadPoolSize;
      private boolean hasCapacity;

      public Builder queuedCount(long queuedCount) {
        this.queuedCount = queuedCount;
        return this;
      }

      public Builder activeCount(int activeCount) {
        this.activeCount = activeCount;
        return this;
      }

      public Builder maxQueueSize(int maxQueueSize) {
        this.maxQueueSize = maxQueueSize;
        return this;
      }

      public Builder threadPoolSize(int threadPoolSize) {
        this.threadPoolSize = threadPoolSize;
        return this;
      }

      public Builder hasCapacity(boolean hasCapacity) {
        this.hasCapacity = hasCapacity;
        return this;
      }

      public QueueStatus build() {
        return new QueueStatus(queuedCount, activeCount, maxQueueSize, threadPoolSize, hasCapacity);
      }
    }

    public static Builder builder() {
      return new Builder();
    }

    private final long queuedCount;
    private final int activeCount;
    private final int maxQueueSize;

    private final int threadPoolSize;

    private final boolean hasCapacity;

    public QueueStatus(long queuedCount, int activeCount, int maxQueueSize, int threadPoolSize,
        boolean hasCapacity) {
      this.queuedCount = queuedCount;
      this.activeCount = activeCount;
      this.maxQueueSize = maxQueueSize;
      this.threadPoolSize = threadPoolSize;
      this.hasCapacity = hasCapacity;
    }

    // Getters
    public long getQueuedCount() {
      return queuedCount;
    }

    public int getActiveCount() {
      return activeCount;
    }

    public int getMaxQueueSize() {
      return maxQueueSize;
    }

    public int getThreadPoolSize() {
      return threadPoolSize;
    }

    public boolean isHasCapacity() {
      return hasCapacity;
    }
  }

  private final FillRequestRepository fillRequestRepository;
  private final FillRequestMappingRepository fillRequestMappingRepository;
  private final FormRepository formRepository;
  private final QuestionRepository questionRepository;
  private final GoogleFormService googleFormService;
  private final DataFillCampaignService dataFillCampaignService;
  private final ScheduleDistributionService scheduleDistributionService;
  private final GoogleSheetsService googleSheetsService;
  private final FillRequestRealtimeGateway realtimeGateway;
  private final CurrentUserUtil currentUserUtil;

  @Value("${google.form.thread-pool-size:5}")
  private int threadPoolSize;

  @Value("${queue.max-size:100}")
  private int maxQueueSize;

  @Value("${queue.check-interval:30000}")
  private long checkInterval;
  private ExecutorService queueExecutor;
  private AtomicBoolean isRunning = new AtomicBoolean(false);

  private AtomicInteger activeRequests = new AtomicInteger(0);

  @PostConstruct
  public void init() {
    log.info("QueueManagementService: Initializing with thread pool size: {}, max queue size: {}",
        threadPoolSize, maxQueueSize);
    queueExecutor = Executors.newFixedThreadPool(threadPoolSize);
    isRunning.set(true);
  }

  @PreDestroy
  public void cleanup() {
    log.info("QueueManagementService: Shutting down...");
    isRunning.set(false);
    if (queueExecutor != null) {
      queueExecutor.shutdown();
      try {
        if (!queueExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
          queueExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        queueExecutor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Add a fill request to the queue
   */
  @Transactional
  public boolean addToQueue(FillRequest fillRequest) {
    try {
      // Check if queue is full
      long queuedCount = fillRequestRepository.countByStatus(FillRequestStatusEnum.QUEUED);
      if (queuedCount >= maxQueueSize) {
        log.warn("Queue is full ({}), rejecting request: {}", maxQueueSize, fillRequest.getId());
        return false;
      }

      // Get next queue position
      Integer nextPosition =
          fillRequestRepository.findNextQueuePosition(FillRequestStatusEnum.QUEUED);

      // Update fill request to QUEUED status
      fillRequest.setStatus(FillRequestStatusEnum.QUEUED);
      fillRequest.setQueuePosition(nextPosition);
      fillRequest.setQueuedAt(DateTimeUtil.now());
      fillRequestRepository.save(fillRequest);

      log.info("Added fill request {} to queue at position {}", fillRequest.getId(), nextPosition);

      // Emit realtime update
      emitQueueUpdate(fillRequest);

      return true;
    } catch (Exception e) {
      log.error("Failed to add fill request {} to queue", fillRequest.getId(), e);
      return false;
    }
  }

  /**
   * Check if thread pool has available capacity
   */
  public boolean hasAvailableCapacity() {
    // Use activeRequests counter for more accurate capacity checking
    int activeRequestsCount = activeRequests.get();
    boolean hasCapacity = activeRequestsCount < threadPoolSize;

    log.debug("Capacity check: active={}, max={}, hasCapacity={}", activeRequestsCount,
        threadPoolSize, hasCapacity);

    return hasCapacity;
  }

  /**
   * Get current queue status
   */
  public QueueStatus getQueueStatus() {
    long queuedCount = fillRequestRepository.countByStatus(FillRequestStatusEnum.QUEUED);
    int activeCount = activeRequests.get();

    return QueueStatus.builder().queuedCount(queuedCount).activeCount(activeCount)
        .maxQueueSize(maxQueueSize).threadPoolSize(threadPoolSize)
        .hasCapacity(hasAvailableCapacity()).build();
  }

  /**
   * Get current active count
   */
  public int getActiveCount() {
    return activeRequests.get();
  }

  /**
   * Get thread pool size
   */
  public int getThreadPoolSize() {
    return threadPoolSize;
  }

  /**
   * Retry failed requests that haven't exceeded max retries
   */
  @Transactional
  public int retryFailedRequests() {
    try {
      List<FillRequest> failedRequests =
          fillRequestRepository.findFailedRequestsForRetry(FillRequestStatusEnum.FAILED);

      if (failedRequests.isEmpty()) {
        log.info("No failed requests to retry");
        return 0;
      }

      int retriedCount = 0;
      for (FillRequest failedRequest : failedRequests) {
        try {
          log.info("Retrying failed request: {} (attempt {}/{})", failedRequest.getId(),
              failedRequest.getRetryCount() + 1, failedRequest.getMaxRetries());

          // Reset retry count and add to queue
          failedRequest.setRetryCount(0);
          failedRequest.setStatus(FillRequestStatusEnum.QUEUED);
          fillRequestRepository.save(failedRequest);

          if (addToQueue(failedRequest)) {
            retriedCount++;
            log.info("Successfully re-queued failed request: {}", failedRequest.getId());
          } else {
            log.warn("Failed to re-queue request: {}", failedRequest.getId());
          }
        } catch (Exception e) {
          log.error("Error retrying request: {}", failedRequest.getId(), e);
        }
      }

      log.info("Retry process completed. {} requests re-queued", retriedCount);
      return retriedCount;

    } catch (Exception e) {
      log.error("Error in retry process", e);
      return 0;
    }
  }

  /**
   * Scheduled task to process queued requests
   */
  @Scheduled(fixedRateString = "${queue.check-interval:30000}")
  @Transactional
  public void processQueuedRequests() {
    if (!isRunning.get()) {
      return;
    }

    try {
      // Check if we have capacity to process more requests
      if (!hasAvailableCapacity()) {
        log.debug("No capacity available, skipping queue processing");
        return;
      }

      // Get all queued requests ordered by priority (ignore startDate for immediate processing)
      List<FillRequest> queuedRequests =
          fillRequestRepository.findQueuedRequestsOrderedByPriority(FillRequestStatusEnum.QUEUED);

      if (queuedRequests.isEmpty()) {
        log.debug("No queued requests to process");
        return;
      }

      log.info("Found {} queued requests, checking capacity for processing", queuedRequests.size());

      // Separate human and non-human requests for better prioritization
      List<FillRequest> nonHumanRequests =
          queuedRequests.stream().filter(req -> !req.isHumanLike()).collect(Collectors.toList());

      List<FillRequest> humanRequests =
          queuedRequests.stream().filter(req -> req.isHumanLike()).collect(Collectors.toList());

      log.debug("Queue breakdown: {} non-human requests, {} human requests",
          nonHumanRequests.size(), humanRequests.size());

      // Process up to available capacity
      int availableSlots = threadPoolSize - activeRequests.get();
      int processed = 0;

      // First, process non-human requests (higher priority)
      for (FillRequest request : nonHumanRequests) {
        if (processed >= availableSlots) {
          log.debug("Reached capacity limit ({} slots), stopping processing", availableSlots);
          break;
        }

        // Check if request can be processed (startDate check)
        if (request.getStartDate() != null && request.getStartDate().isAfter(DateTimeUtil.now())) {
          log.debug("Skipping non-human request {} - startDate {} is in the future",
              request.getId(), DateTimeUtil.formatForLog(request.getStartDate()));
          continue;
        }

        if (processQueuedRequest(request)) {
          processed++;
          log.info("Started processing non-human queued request: {} ({}/{})", request.getId(),
              processed, availableSlots);
        }
      }

      // Then, process human requests if there's still capacity
      for (FillRequest request : humanRequests) {
        if (processed >= availableSlots) {
          log.debug("Reached capacity limit ({} slots), stopping processing", availableSlots);
          break;
        }

        // Check if request can be processed (startDate check)
        if (request.getStartDate() != null && request.getStartDate().isAfter(DateTimeUtil.now())) {
          log.debug("Skipping human request {} - startDate {} is in the future", request.getId(),
              DateTimeUtil.formatForLog(request.getStartDate()));
          continue;
        }

        if (processQueuedRequest(request)) {
          processed++;
          log.info("Started processing human queued request: {} ({}/{})", request.getId(),
              processed, availableSlots);
        }
      }

      if (processed > 0) {
        log.info("Successfully started processing {} queued requests ({} non-human, {} human)",
            processed,
            nonHumanRequests.stream()
                .filter(req -> req.getStatus() == FillRequestStatusEnum.IN_PROCESS).count(),
            humanRequests.stream()
                .filter(req -> req.getStatus() == FillRequestStatusEnum.IN_PROCESS).count());
      } else {
        log.debug("No requests were processed (capacity: {}, available: {})", threadPoolSize,
            availableSlots);
      }

    } catch (Exception e) {
      log.error("Error processing queued requests", e);
    }
  }

  /**
   * Process a single queued request
   */
  private boolean processQueuedRequest(FillRequest fillRequest) {
    try {
      log.info("Processing queued request: {} (position: {})", fillRequest.getId(),
          fillRequest.getQueuePosition());

      // Remove from queue and start processing
      fillRequest.setStatus(FillRequestStatusEnum.IN_PROCESS);
      fillRequest.setQueuePosition(null);
      fillRequest.setQueuedAt(null);
      fillRequestRepository.save(fillRequest);

      // Update queue positions for remaining requests
      fillRequestRepository.decrementQueuePositions(FillRequestStatusEnum.QUEUED,
          fillRequest.getQueuePosition());

      // Start processing the request
      startRequestProcessing(fillRequest);

      return true;
    } catch (Exception e) {
      log.error("Failed to process queued request: {}", fillRequest.getId(), e);

      // Increment retry count
      fillRequest.setRetryCount(fillRequest.getRetryCount() + 1);
      if (fillRequest.getRetryCount() >= fillRequest.getMaxRetries()) {
        fillRequest.setStatus(FillRequestStatusEnum.FAILED);
        log.error("Request {} exceeded max retries, marking as FAILED", fillRequest.getId());
      }
      fillRequestRepository.save(fillRequest);

      return false;
    }
  }

  /**
   * Start processing a fill request
   */
  private void startRequestProcessing(FillRequest fillRequest) {
    activeRequests.incrementAndGet();

    CompletableFuture.runAsync(() -> {
      try {
        log.info("Starting processing for request: {}", fillRequest.getId());

        // Determine request type and process accordingly
        if (isDataFillRequest(fillRequest)) {
          processDataFillRequest(fillRequest);
        } else {
          processRegularFillRequest(fillRequest);
        }

      } catch (Exception e) {
        log.error("Error processing request: {}", fillRequest.getId(), e);
        handleProcessingError(fillRequest, e);
      } finally {
        activeRequests.decrementAndGet();
      }
    }, queueExecutor);
  }

  /**
   * Check if this is a data fill request
   */
  private boolean isDataFillRequest(FillRequest fillRequest) {
    List<FillRequestMapping> mappings =
        fillRequestMappingRepository.findByFillRequestId(fillRequest.getId());
    return !mappings.isEmpty();
  }

  /**
   * Process data fill request
   */
  private void processDataFillRequest(FillRequest fillRequest) {
    try {
      // Get form and questions
      Form form = formRepository.findById(fillRequest.getForm().getId())
          .orElseThrow(() -> new RuntimeException("Form not found"));
      List<Question> questions = questionRepository.findByForm(form);

      // Get mappings
      List<FillRequestMapping> mappings =
          fillRequestMappingRepository.findByFillRequestId(fillRequest.getId());

      // Reconstruct DataFillRequestDTO
      DataFillRequestDTO reconstructedRequest = reconstructDataFillRequest(fillRequest, mappings);

      // Create schedule distribution
      List<ScheduleDistributionService.ScheduledTask> schedule =
          scheduleDistributionService.distributeSchedule(fillRequest.getSurveyCount(),
              fillRequest.getStartDate(), fillRequest.getEndDate(), fillRequest.isHumanLike(),
              fillRequest.getCompletedSurvey());

      // Execute campaign
      dataFillCampaignService
          .executeCampaign(fillRequest, reconstructedRequest, questions, schedule)
          .exceptionally(throwable -> {
            log.error("Campaign execution failed for request: {}", fillRequest.getId(), throwable);
            handleProcessingError(fillRequest, throwable);
            return null;
          });

    } catch (Exception e) {
      log.error("Failed to process data fill request: {}", fillRequest.getId(), e);
      handleProcessingError(fillRequest, e);
    }
  }

  /**
   * Process regular fill request
   */
  private void processRegularFillRequest(FillRequest fillRequest) {
    try {
      // Use existing GoogleFormService to process
      googleFormService.fillForm(fillRequest.getId());
    } catch (Exception e) {
      log.error("Failed to process regular fill request: {}", fillRequest.getId(), e);
      handleProcessingError(fillRequest, e);
    }
  }

  /**
   * Handle processing errors
   */
  private void handleProcessingError(FillRequest fillRequest, Throwable error) {
    try {
      // Use atomic update to avoid optimistic locking conflicts
      int newRetryCount = fillRequest.getRetryCount() + 1;

      if (newRetryCount >= fillRequest.getMaxRetries()) {
        // Update status to FAILED atomically
        int updated = fillRequestRepository.updateStatusAndRetryCount(fillRequest.getId(),
            FillRequestStatusEnum.FAILED, newRetryCount);
        if (updated > 0) {
          log.error("Request {} exceeded max retries, marking as FAILED", fillRequest.getId());
        }
      } else {
        // Re-queue for retry and update retry count atomically
        addToQueue(fillRequest);
        int updated = fillRequestRepository.updateRetryCount(fillRequest.getId(), newRetryCount);
        if (updated > 0) {
          log.info("Re-queued request {} for retry (attempt {}/{})", fillRequest.getId(),
              newRetryCount, fillRequest.getMaxRetries());
        }
      }

      // Emit update with fresh data
      FillRequest updatedRequest =
          fillRequestRepository.findById(fillRequest.getId()).orElse(fillRequest);
      emitQueueUpdate(updatedRequest);

    } catch (Exception e) {
      log.error("Failed to handle processing error for request: {}", fillRequest.getId(), e);
    }
  }

  /**
   * Reconstruct DataFillRequestDTO from stored data
   */
  private DataFillRequestDTO reconstructDataFillRequest(FillRequest fillRequest,
      List<FillRequestMapping> mappings) {
    DataFillRequestDTO dto = new DataFillRequestDTO();
    dto.setFormName(fillRequest.getForm().getName());
    dto.setFormId(fillRequest.getForm().getId().toString());
    dto.setSheetLink(mappings.get(0).getSheetLink());
    dto.setSubmissionCount(fillRequest.getSurveyCount());
    dto.setPricePerSurvey(fillRequest.getPricePerSurvey());
    dto.setIsHumanLike(fillRequest.isHumanLike());
    dto.setStartDate(fillRequest.getStartDate());
    dto.setEndDate(fillRequest.getEndDate());

    // Convert mappings
    List<com.dienform.tool.dienformtudong.datamapping.dto.request.ColumnMapping> questionMappings =
        new java.util.ArrayList<>();
    for (FillRequestMapping mapping : mappings) {
      com.dienform.tool.dienformtudong.datamapping.dto.request.ColumnMapping columnMapping =
          new com.dienform.tool.dienformtudong.datamapping.dto.request.ColumnMapping();
      columnMapping.setQuestionId(mapping.getQuestionId().toString());
      columnMapping.setColumnName(mapping.getColumnName());
      questionMappings.add(columnMapping);
    }
    dto.setMappings(questionMappings);

    return dto;
  }

  /**
   * Emit realtime update for queue changes
   */
  private void emitQueueUpdate(FillRequest fillRequest) {
    try {
      String userId = currentUserUtil.getCurrentUserIdIfPresent().map(UUID::toString).orElse(null);

      com.dienform.realtime.dto.FillRequestUpdateEvent evt =
          com.dienform.realtime.dto.FillRequestUpdateEvent.builder()
              .formId(fillRequest.getForm().getId().toString())
              .requestId(fillRequest.getId().toString()).status(fillRequest.getStatus().name())
              .completedSurvey(fillRequest.getCompletedSurvey())
              .surveyCount(fillRequest.getSurveyCount())
              .updatedAt(java.time.Instant.now().toString()).build();

      realtimeGateway.emitUpdateWithUser(fillRequest.getForm().getId().toString(), evt, userId);
    } catch (Exception e) {
      log.debug("Failed to emit queue update: {}", e.getMessage());
    }
  }
}
