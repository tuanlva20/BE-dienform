package com.dienform.tool.dienformtudong.fillrequest.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.common.util.CopyUtil;
import com.dienform.common.util.CurrentUserUtil;
import com.dienform.tool.dienformtudong.datamapping.dto.request.DataFillRequestDTO;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestRepository;
import com.dienform.tool.dienformtudong.fillrequest.service.ScheduleDistributionService.ScheduledTask;
import com.dienform.tool.dienformtudong.fillrequest.validator.DataFillValidator;
import com.dienform.tool.dienformtudong.googleform.service.GoogleFormService;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.entity.QuestionOption;
import com.dienform.tool.dienformtudong.question.repository.QuestionRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class DataFillCampaignService {

  // Enforce max concurrent form submissions per fill-request across all code paths
  private static final Map<UUID, Semaphore> perRequestSemaphores = new ConcurrentHashMap<>();

  @Autowired
  private GoogleFormService googleFormService;

  @Autowired
  private com.dienform.tool.dienformtudong.datamapping.service.GoogleSheetsService googleSheetsService;

  @Autowired
  private DataFillValidator dataFillValidator;

  @Autowired
  private FillRequestRepository fillRequestRepository;

  @Autowired
  private FillRequestCounterService fillRequestCounterService;

  @Autowired
  private QuestionRepository questionRepository;

  @Value("${google.form.thread-pool-size:2}")
  private int threadPoolSize;

  @Value("${google.form.fast-timeout-seconds:300}")
  private long fastTimeoutSeconds;

  @Value("${google.form.human-timeout-seconds:1800}")
  private long humanTimeoutSeconds;

  @Value("${google.form.heavy-load-threshold:250}")
  private int heavyLoadThreshold;

  @Value("${google.form.heavy-timeout-multiplier:2.0}")
  private double heavyTimeoutMultiplier;

  // Using ThreadPoolManager instead of local executorService

  @Value("${google.form.per-request.max-inflight:2}")
  private int perRequestMaxInflight;

  @Autowired
  private com.dienform.realtime.FillRequestRealtimeGateway realtimeGateway;

  @Autowired
  private CurrentUserUtil currentUserUtil;

  @Autowired
  private ThreadPoolManager threadPoolManager;

  @Autowired
  private ScheduleDistributionService scheduleDistributionService;

  @Autowired
  private ThreadPoolMonitorService threadPoolMonitorService;

  @Autowired
  private AdaptiveTimeoutService adaptiveTimeoutService;

  // Scheduler for delayed task submission
  private ScheduledExecutorService taskScheduler;

  @PostConstruct
  public void init() {
    log.info("DataFillCampaignService: Using ThreadPoolManager for separate human/fast executors");
    // Initialize task scheduler for delayed submissions with 2 threads to prevent bottleneck
    // Use ScheduledThreadPoolExecutor directly to enable core thread timeout
    java.util.concurrent.ScheduledThreadPoolExecutor scheduledExecutor =
        new java.util.concurrent.ScheduledThreadPoolExecutor(2);
    scheduledExecutor.setRemoveOnCancelPolicy(true);
    scheduledExecutor.setKeepAliveTime(60, java.util.concurrent.TimeUnit.SECONDS);
    scheduledExecutor.allowCoreThreadTimeOut(true);
    taskScheduler = scheduledExecutor;
    log.info(
        "DataFillCampaignService: Task scheduler initialized with 2 threads for delayed submissions (with core timeout)");
  }

  @PreDestroy
  public void cleanup() {
    // ThreadPoolManager handles its own cleanup
    if (taskScheduler != null) {
      taskScheduler.shutdown();
      try {
        if (!taskScheduler.awaitTermination(60, TimeUnit.SECONDS)) {
          taskScheduler.shutdownNow();
        }
      } catch (InterruptedException e) {
        taskScheduler.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
    log.info("DataFillCampaignService: Cleanup completed");
  }

  /**
   * Execute data fill campaign based on schedule (legacy method for backward compatibility)
   */
  public CompletableFuture<Void> executeCampaign(FillRequest fillRequest,
      DataFillRequestDTO originalRequest, List<Question> questions) {

    // Create schedule using existing logic
    List<ScheduleDistributionService.ScheduledTask> schedule = scheduleDistributionService
        .distributeSchedule(fillRequest.getSurveyCount(), fillRequest.getStartDate(),
            fillRequest.getEndDate(), fillRequest.isHumanLike(), fillRequest.getCompletedSurvey());

    return executeCampaign(fillRequest, originalRequest, questions, schedule);
  }

  /**
   * Execute regular fill request with same adaptive timeout and circuit breaker logic
   */
  public CompletableFuture<Void> executeRegularCampaign(FillRequest fillRequest,
      List<Question> questions) {

    log.info(
        "Starting regular campaign execution for fillRequest: {} with {} forms (human-like: {}, maxInflight: {})",
        fillRequest.getId(), fillRequest.getSurveyCount(), fillRequest.isHumanLike(),
        Math.max(1, perRequestMaxInflight));

    // Create schedule distribution for regular fill request
    List<ScheduleDistributionService.ScheduledTask> schedule = scheduleDistributionService
        .distributeSchedule(fillRequest.getSurveyCount(), fillRequest.getStartDate(),
            fillRequest.getEndDate(), fillRequest.isHumanLike(), fillRequest.getCompletedSurvey());

    return executeRegularCampaignCore(fillRequest, questions, schedule);
  }

  /**
   * Execute batch tasks (for batch processing)
   */
  public CompletableFuture<Void> executeBatchTasks(FillRequest fillRequest,
      DataFillRequestDTO originalRequest, List<Question> questions,
      List<ScheduleDistributionService.ScheduledTask> batchTasks, ExecutorService executor) {

    log.info("Executing batch tasks for fillRequest: {} with {} tasks", fillRequest.getId(),
        batchTasks.size());

    // Use the same logic as executeCampaign but with provided tasks and executor
    // Extract the core execution logic from executeCampaign method
    // Note: No @Transactional here to avoid connection leaks with async operations
    return executeCampaignCore(fillRequest, originalRequest, questions, batchTasks, executor);
  }

  /**
   * Execute data fill campaign based on schedule
   */
  public CompletableFuture<Void> executeCampaign(FillRequest fillRequest,
      DataFillRequestDTO originalRequest, List<Question> questions, List<ScheduledTask> schedule) {

    ExecutorService executor = threadPoolManager.getExecutor(fillRequest.isHumanLike());
    return executeCampaignCore(fillRequest, originalRequest, questions, schedule, executor);
  }

  /**
   * Emit progress update in a separate transaction
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void emitProgressUpdateInTransaction(UUID fillRequestId) {
    com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest current =
        fillRequestRepository.findById(fillRequestId).orElse(null);
    if (current != null && current.getForm() != null) {
      // Only emit if there's meaningful progress (completedSurvey > 0)
      if (current.getCompletedSurvey() > 0) {
        com.dienform.realtime.dto.FillRequestUpdateEvent evt =
            com.dienform.realtime.dto.FillRequestUpdateEvent.builder()
                .formId(current.getForm().getId().toString()).requestId(current.getId().toString())
                .status(current.getStatus() == null
                    ? com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum.IN_PROCESS
                        .name()
                    : current.getStatus().name())
                .completedSurvey(current.getCompletedSurvey()).surveyCount(current.getSurveyCount())
                .updatedAt(java.time.Instant.now().toString()).build();

        // Emit to form room only (avoid duplicate user-specific emissions)
        realtimeGateway.emitUpdate(current.getForm().getId().toString(), evt);

        log.debug("Emitted progress update for fillRequest: {} - {}/{}", fillRequestId,
            current.getCompletedSurvey(), current.getSurveyCount());
      }
    }
  }

  /**
   * Increment failed survey count in a separate transaction
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void incrementFailedSurveyInTransaction(UUID fillRequestId) {
    fillRequestCounterService.incrementFailedSurvey(fillRequestId);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  protected void updateFillRequestStatus(FillRequest fillRequest, FillRequestStatusEnum newStatus) {
    try {
      // Use optimistic locking service for status updates
      boolean success = fillRequestCounterService.updateStatus(fillRequest.getId(), newStatus);

      if (success) {
        log.info("Updated fill request {} status to: {}", fillRequest.getId(), newStatus.name());
      } else {
        // Increment failed survey count in database
        incrementFailedSurveyCount(fillRequest.getId());
        log.warn("Failed to update fill request {} status to: {}", fillRequest.getId(),
            newStatus.name());
      }
    } catch (Exception e) {
      log.error("Failed to update fill request status: {}", fillRequest.getId(), e);
    }
  }

  private Semaphore getSemaphoreForRequest(UUID fillRequestId) {
    int permits = Math.max(1, perRequestMaxInflight);
    return perRequestSemaphores.computeIfAbsent(fillRequestId, k -> new Semaphore(permits));
  }

  /**
   * Execute regular campaign core logic with adaptive timeout and circuit breaker
   */
  private CompletableFuture<Void> executeRegularCampaignCore(FillRequest fillRequest,
      List<Question> questions, List<ScheduleDistributionService.ScheduledTask> schedule) {

    ExecutorService executor = threadPoolManager.getExecutor(fillRequest.isHumanLike());

    log.info(
        "Starting regular fill campaign for request: {} with {} tasks using {} executor (humanLike: {})",
        fillRequest.getId(), schedule.size(),
        fillRequest.isHumanLike() ? "human-like" : "fast-mode", fillRequest.isHumanLike());

    // Check if already completed
    if (fillRequest.getCompletedSurvey() >= fillRequest.getSurveyCount()) {
      log.info("Regular fill request {} is already completed ({}), updating status to COMPLETED",
          fillRequest.getId(), fillRequest.getCompletedSurvey());
      updateFillRequestStatus(fillRequest, FillRequestStatusEnum.COMPLETED);
      return CompletableFuture.completedFuture(null);
    }

    // Calculate remaining surveys to complete
    int remainingSurveys = fillRequest.getSurveyCount() - fillRequest.getCompletedSurvey();
    log.info("Regular fill request {} has {} completed, needs {} more surveys to complete",
        fillRequest.getId(), fillRequest.getCompletedSurvey(), remainingSurveys);

    // Adjust schedule to only process remaining surveys
    List<ScheduledTask> remainingSchedule =
        schedule.subList(0, Math.min(remainingSurveys, schedule.size()));
    // Enforce stable ascending order by row index to guarantee lower rows execute first
    remainingSchedule.sort(java.util.Comparator.comparingInt(ScheduledTask::getRowIndex));
    log.info("Adjusted schedule to {} tasks for remaining surveys (sorted by rowIndex asc)",
        remainingSchedule.size());

    // Set initial status to IN_PROCESS
    updateFillRequestStatus(fillRequest, FillRequestStatusEnum.IN_PROCESS);

    CompletableFuture<Void> executionFuture = new CompletableFuture<>();

    try {
      // Initialize data needed for form filling
      Map<UUID, Question> questionMap = new HashMap<>();
      for (Question q : questions) {
        questionMap.put(q.getId(), q);
      }

      // Process tasks with per-request windowed submission to avoid monopolizing the queue
      List<CompletableFuture<Boolean>> futures = new ArrayList<>();
      int totalTasks = remainingSchedule.size();

      java.util.concurrent.atomic.AtomicInteger nextIndex =
          new java.util.concurrent.atomic.AtomicInteger(0);
      java.util.concurrent.atomic.AtomicInteger inFlight =
          new java.util.concurrent.atomic.AtomicInteger(0);

      Runnable submitNext = new Runnable() {
        @Override
        public void run() {
          int maxInflight = Math.max(1, perRequestMaxInflight);
          while (inFlight.get() < maxInflight) {
            int idx = nextIndex.getAndIncrement();
            if (idx >= remainingSchedule.size()) {
              return;
            }

            ScheduledTask task = remainingSchedule.get(idx);
            inFlight.incrementAndGet();

            CompletableFuture<Boolean> f =
                scheduleFormFill(fillRequest, null, questionMap, null, task, totalTasks, executor)
                    .whenComplete((ok, ex) -> {
                      inFlight.decrementAndGet();
                      // Submit next task for this fill when one completes
                      this.run();
                    });
            futures.add(f);
          }
        }
      };

      // Kick off initial window
      submitNext.run();

      // Do NOT create a separate allOf/aggregation here.
      // Finalization is handled in the per-task whenComplete block when completed == totalTasks.

    } catch (Exception e) {
      log.error("Error in regular campaign execution: {}", fillRequest.getId(), e);
      updateFillRequestStatus(fillRequest, FillRequestStatusEnum.FAILED);
      executionFuture.completeExceptionally(e);
    }

    return executionFuture;
  }

  /**
   * Core execution logic extracted from executeCampaign for reuse
   */
  private CompletableFuture<Void> executeCampaignCore(FillRequest fillRequest,
      DataFillRequestDTO originalRequest, List<Question> questions,
      List<ScheduleDistributionService.ScheduledTask> schedule, ExecutorService executor) {
    log.info(
        "Starting data fill campaign for request: {} with {} tasks using {} executor (humanLike: {})",
        fillRequest.getId(), schedule.size(),
        fillRequest.isHumanLike() ? "human-like" : "fast-mode", fillRequest.isHumanLike());
    log.info("Campaign details - isHumanLike: {}, startDate: {}, endDate: {}",
        fillRequest.isHumanLike(), fillRequest.getStartDate(), fillRequest.getEndDate());

    // Check if already completed
    if (fillRequest.getCompletedSurvey() >= fillRequest.getSurveyCount()) {
      log.info("Data fill request {} is already completed ({}), updating status to COMPLETED",
          fillRequest.getId(), fillRequest.getCompletedSurvey());
      updateFillRequestStatus(fillRequest, FillRequestStatusEnum.COMPLETED);
      CompletableFuture<Void> completedFuture = new CompletableFuture<>();
      completedFuture.complete(null);
      return completedFuture;
    }

    // Calculate remaining surveys to complete
    int remainingSurveys = fillRequest.getSurveyCount() - fillRequest.getCompletedSurvey();
    log.info("Data fill request {} has {} completed, needs {} more surveys to complete",
        fillRequest.getId(), fillRequest.getCompletedSurvey(), remainingSurveys);

    // Adjust schedule to only process remaining surveys
    List<ScheduledTask> remainingSchedule =
        schedule.subList(0, Math.min(remainingSurveys, schedule.size()));
    // Enforce stable ascending order by row index to guarantee lower rows execute first
    remainingSchedule.sort(java.util.Comparator.comparingInt(ScheduledTask::getRowIndex));
    log.info("Adjusted schedule to {} tasks for remaining surveys (sorted by rowIndex asc)",
        remainingSchedule.size());

    // Set initial status to IN_PROCESS and ensure user is in room
    updateFillRequestStatus(fillRequest, FillRequestStatusEnum.IN_PROCESS);
    ensureUserInRoom(fillRequest);

    CompletableFuture<Void> executionFuture = new CompletableFuture<>();

    try {
      // Initialize data needed for form filling
      Map<UUID, Question> questionMap = new HashMap<>();
      for (Question q : questions) {
        Question detachedQuestion = CopyUtil.detachedCopy(q, source -> {
          Question copy = new Question(source);
          List<QuestionOption> detachedOptions = source.getOptions().stream().map(opt -> {
            QuestionOption optionCopy = new QuestionOption(opt);
            optionCopy.setQuestion(copy);
            return optionCopy;
          }).collect(Collectors.toList());
          copy.setOptions(detachedOptions);
          return copy;
        });
        questionMap.put(q.getId(), detachedQuestion);
      }

      // Read sheet data
      List<Map<String, Object>> sheetData =
          googleSheetsService.getSheetData(originalRequest.getSheetLink());

      if (sheetData == null || sheetData.isEmpty()) {
        log.error("No data found in sheet for request: {}", fillRequest.getId());
        updateFillRequestStatus(fillRequest, FillRequestStatusEnum.FAILED);
        executionFuture.complete(null);
        return executionFuture;
      }

      // Check if there are enough data rows for the remaining survey count
      if (sheetData.size() < remainingSurveys) {
        log.warn(
            "Not enough data rows in sheet for remaining surveys. Required: {}, Available: {}. Data will be reused from the beginning.",
            remainingSurveys, sheetData.size());
      }

      AtomicInteger completedTasks = new AtomicInteger(0);
      AtomicInteger successfulTasks = new AtomicInteger(0);
      AtomicInteger submittedTasks = new AtomicInteger(0);
      int totalTasks = remainingSchedule.size();

      log.info("TASK EXECUTION PLAN: Total {} tasks will be processed with {} executor", totalTasks,
          fillRequest.isHumanLike() ? "human-like" : "fast-mode");
      log.info("EXPECTED FLOW: Tasks will be distributed across {} executor pool",
          fillRequest.isHumanLike() ? "human-like" : "fast-mode");

      // Windowed delayed submission with per-request cap
      log.info("Scheduling {} tasks with delayed submission for fillRequest: {}",
          remainingSchedule.size(), fillRequest.getId());

      java.util.concurrent.atomic.AtomicInteger nextIdx =
          new java.util.concurrent.atomic.AtomicInteger(0);
      java.util.concurrent.atomic.AtomicInteger inFlightTasks =
          new java.util.concurrent.atomic.AtomicInteger(0);

      // Determine scheduling strategy based on task density
      boolean useExecutionTime = shouldUseExecutionTime(fillRequest.getStartDate(),
          fillRequest.getEndDate(), remainingSchedule.size());
      log.info("Using {} scheduling for fillRequest {} (density-based decision)",
          useExecutionTime ? "executionTime" : "delaySeconds", fillRequest.getId());

      java.util.function.Consumer<Integer> scheduleOne = new java.util.function.Consumer<>() {
        @Override
        public void accept(Integer ignored) {
          int maxInflight = Math.max(1, perRequestMaxInflight);
          while (inFlightTasks.get() < maxInflight) {
            int i = nextIdx.getAndIncrement();
            if (i >= remainingSchedule.size())
              return;
            ScheduledTask task = remainingSchedule.get(i);
            final int taskIndex = i + 1;

            // Calculate submission delay based on strategy
            long submissionDelayMs;
            LocalDateTime now = com.dienform.common.util.DateTimeUtil.now();
            if (useExecutionTime && task.getExecutionTime() != null) {
              // Use executionTime with small jitter for human-like behavior
              long diff = java.time.Duration.between(now, task.getExecutionTime()).toMillis();
              long jitterMs = 5000L + new java.util.Random().nextInt(15000); // 5-20s jitter
              submissionDelayMs = Math.max(0L, diff) + jitterMs;
              log.debug("Task {}: using executionTime {} with {}ms delay ({}s jitter)", taskIndex,
                  task.getExecutionTime(), submissionDelayMs, jitterMs / 1000);
            } else {
              // Use delaySeconds (existing behavior)
              submissionDelayMs = task.getDelaySeconds() * 1000L;
              log.debug("Task {}: using delaySeconds with {}ms delay", taskIndex,
                  submissionDelayMs);
            }

            inFlightTasks.incrementAndGet();

            taskScheduler.schedule(() -> {
              int submitted = submittedTasks.incrementAndGet();
              log.info(
                  "Submitting delayed task {}/{} (row {}) for fillRequest: {} - SUBMITTED: {}/{}",
                  taskIndex, remainingSchedule.size(), task.getRowIndex(), fillRequest.getId(),
                  submitted, totalTasks);

              logThreadPoolStatus("After submitting delayed task " + taskIndex);

              scheduleFormFillWithoutDelay(fillRequest, originalRequest, questionMap, sheetData,
                  task, totalTasks, executor).whenComplete((success, ex) -> {
                    int completed = completedTasks.incrementAndGet();
                    inFlightTasks.decrementAndGet();
                    if (Boolean.TRUE.equals(success)) {
                      successfulTasks.incrementAndGet();
                      if (successfulTasks.get() == 1) {
                        updateFillRequestStatus(fillRequest, FillRequestStatusEnum.IN_PROCESS);
                      }
                    } else {
                      incrementFailedSurveyCount(fillRequest.getId());
                      try {
                        fillRequestCounterService.incrementFailedSurvey(fillRequest.getId());
                      } catch (Exception ignore) {
                      }
                    }

                    if (completed == totalTasks) {
                      log.info("All tasks completed for campaign {}. Successful: {}/{}",
                          fillRequest.getId(), successfulTasks.get(), totalTasks);

                      FillRequest fresh =
                          fillRequestRepository.findById(fillRequest.getId()).orElse(null);
                      if (fresh == null) {
                        updateFillRequestStatus(fillRequest, FillRequestStatusEnum.FAILED);
                        executionFuture.complete(null);
                        return;
                      }

                      FillRequestStatusEnum finalStatus;
                      if (fresh.getCompletedSurvey() >= fresh.getSurveyCount()) {
                        finalStatus = FillRequestStatusEnum.COMPLETED;
                      } else if (successfulTasks.get() == 0 && completed == totalTasks) {
                        finalStatus = FillRequestStatusEnum.FAILED;
                      } else {
                        incrementFailedSurveyCount(fillRequest.getId());
                        finalStatus = FillRequestStatusEnum.IN_PROCESS;
                      }

                      updateFillRequestStatus(fillRequest, finalStatus);
                      try {
                        com.dienform.realtime.dto.FillRequestUpdateEvent evt =
                            com.dienform.realtime.dto.FillRequestUpdateEvent.builder()
                                .formId(fresh.getForm().getId().toString())
                                .requestId(fresh.getId().toString()).status(finalStatus.name())
                                .completedSurvey(fresh.getCompletedSurvey())
                                .surveyCount(fresh.getSurveyCount())
                                .updatedAt(java.time.Instant.now().toString()).build();
                        String userId = null;
                        try {
                          userId = currentUserUtil.getCurrentUserIdIfPresent().map(UUID::toString)
                              .orElse(null);
                        } catch (Exception ignore) {
                        }
                        realtimeGateway.emitUpdateWithUser(fresh.getForm().getId().toString(), evt,
                            userId);
                      } catch (Exception ignore) {
                      }

                      executionFuture.complete(null);
                    } else {
                      // Submit next task in window
                      this.accept(null);
                    }
                  });
            }, submissionDelayMs, TimeUnit.MILLISECONDS);
          }
        }
      };

      // Kick off initial window for delayed submissions
      scheduleOne.accept(null);

      // Validate all tasks were submitted
      int finalSubmittedCount = submittedTasks.get();
      if (finalSubmittedCount != totalTasks) {
        log.error(
            "CRITICAL: Only {}/{} tasks were submitted! Missing tasks detected for fillRequest: {}",
            finalSubmittedCount, totalTasks, fillRequest.getId());
      } else {
        // Increment failed survey count in database
        incrementFailedSurveyCount(fillRequest.getId());
        log.info("SUCCESS: All {}/{} tasks submitted to executor queue for fillRequest: {}",
            finalSubmittedCount, totalTasks, fillRequest.getId());
      }

    } catch (Exception e) {
      log.error("Failed to initialize campaign: {}", fillRequest.getId(), e);

      // Chỉ set QUEUED nếu chưa bắt đầu xử lý
      FillRequest current = fillRequestRepository.findById(fillRequest.getId()).orElse(fillRequest);
      if (current.getStatus() == FillRequestStatusEnum.QUEUED) {
        updateFillRequestStatus(fillRequest, FillRequestStatusEnum.QUEUED);
      } else {
        // Increment failed survey count in database
        incrementFailedSurveyCount(fillRequest.getId());
        // Nếu đã IN_PROCESS thì set FAILED thay vì QUEUED
        updateFillRequestStatus(fillRequest, FillRequestStatusEnum.FAILED);
      }

      executionFuture.complete(null);
    }

    return executionFuture;
  }

  /**
   * Ensure current user is in the form room and send initial updates
   */
  private void ensureUserInRoom(FillRequest fillRequest) {
    try {
      currentUserUtil.getCurrentUserIdIfPresent().ifPresent(uid -> {
        String userId = uid.toString();
        String formId = fillRequest.getForm().getId().toString();

        // Ensure user joins the room
        realtimeGateway.ensureUserJoinedFormRoom(userId, formId);

        // Send bulk state update
        realtimeGateway.emitBulkStateForUser(userId, formId);

        // Send current fill request update
        com.dienform.realtime.dto.FillRequestUpdateEvent evt =
            com.dienform.realtime.dto.FillRequestUpdateEvent.builder().formId(formId)
                .requestId(fillRequest.getId().toString()).status(fillRequest.getStatus().name())
                .completedSurvey(fillRequest.getCompletedSurvey())
                .surveyCount(fillRequest.getSurveyCount())
                .updatedAt(java.time.Instant.now().toString()).build();
        realtimeGateway.emitUpdateForUser(userId, formId, evt);

        log.debug("Ensured user {} is in room for form {} and sent initial updates", userId,
            formId);
      });
    } catch (Exception e) {
      log.warn("Failed to ensure user in room: {}", e.getMessage());
    }
  }

  // ThreadPoolManager handles executor shutdown

  /**
   * Emit progress update to both form room and user-specific room
   */
  private void emitProgressUpdate(UUID fillRequestId) {
    try {
      emitProgressUpdateInTransaction(fillRequestId);
    } catch (Exception e) {
      log.warn("Failed to emit progress update: {}", e.getMessage());
    }
  }

  /**
   * Schedule form fill with delay (legacy method - kept for backward compatibility)
   */
  private CompletableFuture<Boolean> scheduleFormFill(FillRequest fillRequest,
      DataFillRequestDTO originalRequest, Map<UUID, Question> questionMap,
      List<Map<String, Object>> sheetData, ScheduledTask task, int totalTasks,
      ExecutorService executor) {

    // Use SmartTimeoutService to calculate optimal timeout
    long timeoutSeconds = calculateOptimalTimeout(fillRequest, totalTasks);

    log.info(
        "Scheduling form fill for task row {} (fillRequest: {}) - Submitting to executor queue",
        task.getRowIndex(), fillRequest.getId());

    CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
      try {
        // Enforce per-request concurrency with semaphore
        Semaphore semaphore = getSemaphoreForRequest(fillRequest.getId());
        semaphore.acquire();
        log.info("THREAD ASSIGNED: Task row {} started execution on thread: {} (fillRequest: {})",
            task.getRowIndex(), Thread.currentThread().getName(), fillRequest.getId());

        // Use delay from task configuration (improved logic ensures first form has 0 delay)
        long delaySeconds = task.getDelaySeconds();

        if (delaySeconds > 0) {
          log.info("Waiting {} seconds before executing task for row {} on thread: {}",
              delaySeconds, task.getRowIndex(), Thread.currentThread().getName());
          Thread.sleep(delaySeconds * 1000);
        } else {
          // Increment failed survey count in database
          incrementFailedSurveyCount(fillRequest.getId());
          log.debug("Task row {} executing immediately without delay", task.getRowIndex());
        }

        // Execute form fill with retry mechanism
        int maxRetries = 3;
        int retryCount = 0;
        boolean success = false;

        while (retryCount < maxRetries && !success) {
          try {
            success = executeFormFill(fillRequest, originalRequest, questionMap, sheetData, task);
            if (!success) {
              retryCount++;
              if (retryCount < maxRetries) {
                log.warn("Form fill attempt {} failed for row {}. Retrying in 5 seconds...",
                    retryCount, task.getRowIndex());
                Thread.sleep(5000); // Wait 5 seconds before retry
              }
            }
          } catch (Exception e) {
            log.error("Error in form fill attempt {} for row {}: {}", retryCount + 1,
                task.getRowIndex(), e.getMessage());
            retryCount++;
            if (retryCount < maxRetries) {
              Thread.sleep(5000);
            }
          }
        }

        if (!success) {
          log.error("All form fill attempts failed for row {}", task.getRowIndex());
        }

        log.info(
            "THREAD RELEASED: Task row {} finished execution on thread: {} (success: {}, fillRequest: {})",
            task.getRowIndex(), Thread.currentThread().getName(), success, fillRequest.getId());

        return success;
      } catch (Exception e) {
        log.error("Error in scheduled form fill: {}", e.getMessage(), e);
        return false;
      } finally {
        try {
          getSemaphoreForRequest(fillRequest.getId()).release();
        } catch (Exception ignore) {
        }
      }
    }, executor);

    CompletableFuture<Boolean> timeoutFuture =
        future.orTimeout(timeoutSeconds, TimeUnit.SECONDS).exceptionally(throwable -> {
          if (throwable instanceof java.util.concurrent.TimeoutException) {
            // Record timeout event for monitoring
            threadPoolMonitorService.recordTimeout(fillRequest.getId().toString(), totalTasks, 0,
                fillRequest.isHumanLike());
            log.error(
                "Task for row {} timed out after {} seconds (fillRequest: {}, totalTasks: {}, isHuman: {})",
                task.getRowIndex(), timeoutSeconds, fillRequest.getId(), totalTasks,
                fillRequest.isHumanLike());
          } else {
            // Increment failed survey count in database
            incrementFailedSurveyCount(fillRequest.getId());
            log.error("Task for row {} failed with exception (fillRequest: {}): {}",
                task.getRowIndex(), fillRequest.getId(), throwable.getMessage());
          }
          return false;
        });

    log.debug("Scheduled task for row {} with smart timeout protection ({}s) (fillRequest: {})",
        task.getRowIndex(), timeoutSeconds, fillRequest.getId());

    return timeoutFuture;
  }

  /**
   * Schedule form fill WITHOUT delay (new method for delayed submission)
   */
  private CompletableFuture<Boolean> scheduleFormFillWithoutDelay(FillRequest fillRequest,
      DataFillRequestDTO originalRequest, Map<UUID, Question> questionMap,
      List<Map<String, Object>> sheetData, ScheduledTask task, int totalTasks,
      ExecutorService executor) {

    // Use SmartTimeoutService to calculate optimal timeout
    long timeoutSeconds = calculateOptimalTimeout(fillRequest, totalTasks);

    log.info(
        "Scheduling form fill WITHOUT delay for task row {} (fillRequest: {}) - Submitting to executor queue",
        task.getRowIndex(), fillRequest.getId());

    CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
      try {
        // Enforce per-request concurrency with semaphore
        Semaphore semaphore = getSemaphoreForRequest(fillRequest.getId());
        semaphore.acquire();
        log.info("THREAD ASSIGNED: Task row {} started execution on thread: {} (fillRequest: {})",
            task.getRowIndex(), Thread.currentThread().getName(), fillRequest.getId());

        // NO DELAY - task was already delayed by scheduler before submission
        log.debug("Task row {} executing immediately (delay already applied by scheduler)",
            task.getRowIndex());

        // Execute form fill with retry mechanism
        int maxRetries = 3;
        int retryCount = 0;
        boolean success = false;

        while (retryCount < maxRetries && !success) {
          try {
            success = executeFormFillWithoutDelay(fillRequest, originalRequest, questionMap,
                sheetData, task);
            if (!success) {
              retryCount++;
              if (retryCount < maxRetries) {
                log.warn("Form fill attempt {} failed for row {}. Retrying in 5 seconds...",
                    retryCount, task.getRowIndex());
                Thread.sleep(5000); // Wait 5 seconds before retry
              }
            }
          } catch (Exception e) {
            log.error("Error in form fill attempt {} for row {}: {}", retryCount + 1,
                task.getRowIndex(), e.getMessage());
            retryCount++;
            if (retryCount < maxRetries) {
              Thread.sleep(5000);
            }
          }
        }

        if (!success) {
          log.error("All form fill attempts failed for row {}", task.getRowIndex());
        }

        log.info(
            "THREAD RELEASED: Task row {} finished execution on thread: {} (success: {}, fillRequest: {})",
            task.getRowIndex(), Thread.currentThread().getName(), success, fillRequest.getId());

        return success;
      } catch (Exception e) {
        log.error("Error in scheduled form fill: {}", e.getMessage(), e);
        return false;
      } finally {
        try {
          getSemaphoreForRequest(fillRequest.getId()).release();
        } catch (Exception ignore) {
        }
      }
    }, executor);

    CompletableFuture<Boolean> timeoutFuture =
        future.orTimeout(timeoutSeconds, TimeUnit.SECONDS).exceptionally(throwable -> {
          if (throwable instanceof java.util.concurrent.TimeoutException) {
            // Record timeout event for monitoring
            threadPoolMonitorService.recordTimeout(fillRequest.getId().toString(), totalTasks, 0,
                fillRequest.isHumanLike());
            log.error(
                "Task for row {} timed out after {} seconds (fillRequest: {}, totalTasks: {}, isHuman: {})",
                task.getRowIndex(), timeoutSeconds, fillRequest.getId(), totalTasks,
                fillRequest.isHumanLike());
          } else {
            // Increment failed survey count in database
            incrementFailedSurveyCount(fillRequest.getId());
            log.error("Task for row {} failed with exception (fillRequest: {}): {}",
                task.getRowIndex(), fillRequest.getId(), throwable.getMessage());
          }
          return false;
        });

    log.debug("Scheduled task for row {} with smart timeout protection ({}s) (fillRequest: {})",
        task.getRowIndex(), timeoutSeconds, fillRequest.getId());

    return timeoutFuture;
  }

  /**
   * Calculate optimal timeout using AdaptiveTimeoutService
   */
  private long calculateOptimalTimeout(FillRequest fillRequest, int totalTasks) {
    // Get current queue size and thread pool size
    int currentQueueSize = getCurrentQueueSize();
    int threadPoolSize = getThreadPoolSize();

    // Use adaptive timeout calculation
    return adaptiveTimeoutService.calculateFormTimeout(totalTasks, currentQueueSize,
        threadPoolSize);
  }

  /**
   * Get current queue size
   */
  private int getCurrentQueueSize() {
    try {
      ExecutorService executor = threadPoolManager.getExecutor(false); // fast mode executor
      if (executor instanceof java.util.concurrent.ThreadPoolExecutor) {
        java.util.concurrent.ThreadPoolExecutor tpe =
            (java.util.concurrent.ThreadPoolExecutor) executor;
        return tpe.getQueue().size();
      }
    } catch (Exception e) {
      log.warn("Could not get queue size: {}", e.getMessage());
    }
    return 0;
  }

  /**
   * Get thread pool size
   */
  private int getThreadPoolSize() {
    return threadPoolSize;
  }

  /**
   * Helper method to increment failed survey count
   */
  private void incrementFailedSurveyCount(UUID fillRequestId) {
    try {
      incrementFailedSurveyInTransaction(fillRequestId);
      log.info("Incremented failed survey count for request: {}", fillRequestId);
    } catch (Exception e) {
      log.error("Failed to increment failed survey count for request: {}: {}", fillRequestId,
          e.getMessage());
    }
  }

  /**
   * Log thread pool status for debugging
   */
  private void logThreadPoolStatus(String context) {
    threadPoolManager.logThreadPoolStatus(context);
  }

  /**
   * Execute actual form filling with data from sheet row (WITH delay)
   */
  private boolean executeFormFill(FillRequest fillRequest, DataFillRequestDTO originalRequest,
      Map<UUID, Question> questionMap, List<Map<String, Object>> sheetData, ScheduledTask task) {

    log.info("Executing form fill for request: {}, row: {}", fillRequest.getId(),
        task.getRowIndex());

    try {
      // Get data for this row (use absolute row index, fail if out of range)
      int actualRowIndex = task.getRowIndex();
      if (actualRowIndex < 0 || actualRowIndex >= sheetData.size()) {
        log.error("Row index {} out of sheet range {}. Task failed.", actualRowIndex,
            sheetData.size());
        return false;
      }
      Map<String, Object> rowData = sheetData.get(actualRowIndex);
      log.info("Using data from row {} (absolute row index)", actualRowIndex);

      // Build form submission data
      Map<String, String> formData =
          buildFormData(originalRequest, questionMap, rowData, fillRequest);

      // Add human-like delay before submission (only for subsequent forms, not the first one)
      if (task.getDelaySeconds() > 0 && task.getRowIndex() > 0) {
        log.debug("Adding human-like delay of {} seconds before form submission for row {}",
            task.getDelaySeconds(), task.getRowIndex());
        Thread.sleep(task.getDelaySeconds() * 1000L);
      } else if (task.getRowIndex() == 0) {
        log.debug("First form (row 0) - executing immediately without delay");
      }

      // Submit form using browser automation
      String formUrl = fillRequest.getForm().getEditLink();
      boolean success = false;

      // Enhanced retry logic with different strategies
      for (int attempt = 1; attempt <= 3; attempt++) {
        try {
          log.info("Form submission attempt {}/3 for request: {}, row: {}", attempt,
              fillRequest.getId(), task.getRowIndex());

          success = googleFormService.submitFormWithBrowser(fillRequest.getId(),
              fillRequest.getForm().getId(), formUrl, formData);

          if (success) {
            log.info("Form submission successful on attempt {} for request: {}, row: {}", attempt,
                fillRequest.getId(), task.getRowIndex());
            break;
          } else {
            // Increment failed survey count in database
            incrementFailedSurveyCount(fillRequest.getId());
            log.warn("Form submission failed on attempt {} for request: {}, row: {}", attempt,
                fillRequest.getId(), task.getRowIndex());

            // Wait before retry
            if (attempt < 3) {
              Thread.sleep(2000 * attempt); // Progressive delay: 2s, 4s
            }
          }
        } catch (Exception e) {
          log.error("Exception on form submission attempt {} for request: {}, row: {}: {}", attempt,
              fillRequest.getId(), task.getRowIndex(), e.getMessage());

          if (attempt < 3) {
            Thread.sleep(2000 * attempt);
          }
        }
      }

      if (success) {
        log.info("Form submission successful for request: {}, row: {}", fillRequest.getId(),
            task.getRowIndex());
        try {
          // Use simple atomic increment - this was working fine before
          boolean incrementSuccess =
              fillRequestCounterService.incrementCompletedSurvey(fillRequest.getId());
          if (!incrementSuccess) {
            log.warn(
                "Failed to increment completedSurvey for {} after retries (may have reached limit)",
                fillRequest.getId());
          }
          // Emit progress update after successful increment
          emitProgressUpdate(fillRequest.getId());
        } catch (Exception e) {
          log.error("Failed to increment completedSurvey for {}: {}", fillRequest.getId(),
              e.getMessage());
        }
      } else {
        // Increment failed survey count in database
        incrementFailedSurveyCount(fillRequest.getId());
        log.error("Form submission failed for request: {}, row: {}", fillRequest.getId(),
            task.getRowIndex());

        // Increment failed survey count in database
        incrementFailedSurveyCount(fillRequest.getId());
      }

      return success;

    } catch (Exception e) {
      log.error("Error executing form fill for request: {}, row: {}", fillRequest.getId(),
          task.getRowIndex(), e);
      return false;
    }
  }

  /**
   * Execute actual form filling with data from sheet row (WITHOUT delay)
   */
  private boolean executeFormFillWithoutDelay(FillRequest fillRequest,
      DataFillRequestDTO originalRequest, Map<UUID, Question> questionMap,
      List<Map<String, Object>> sheetData, ScheduledTask task) {

    log.info("Executing form fill WITHOUT delay for request: {}, row: {}", fillRequest.getId(),
        task.getRowIndex());

    try {
      // Get data for this row (use absolute row index, fail if out of range)
      int actualRowIndex = task.getRowIndex();
      if (actualRowIndex < 0 || actualRowIndex >= sheetData.size()) {
        log.error("Row index {} out of sheet range {}. Task failed.", actualRowIndex,
            sheetData.size());
        return false;
      }
      Map<String, Object> rowData = sheetData.get(actualRowIndex);
      log.info("Using data from row {} (absolute row index)", actualRowIndex);

      // Build form submission data
      Map<String, String> formData =
          buildFormData(originalRequest, questionMap, rowData, fillRequest);

      // NO DELAY - delay was already applied by scheduler before submission
      log.debug("No delay needed - task was already delayed by scheduler before submission");

      // Submit form using browser automation
      String formUrl = fillRequest.getForm().getEditLink();
      boolean success = false;

      // Enhanced retry logic with different strategies
      for (int attempt = 1; attempt <= 3; attempt++) {
        try {
          log.info("Form submission attempt {}/3 for request: {}, row: {}", attempt,
              fillRequest.getId(), task.getRowIndex());

          success = googleFormService.submitFormWithBrowser(fillRequest.getId(),
              fillRequest.getForm().getId(), formUrl, formData);

          if (success) {
            log.info("Form submission successful on attempt {} for request: {}, row: {}", attempt,
                fillRequest.getId(), task.getRowIndex());
            break;
          } else {
            // Increment failed survey count in database
            incrementFailedSurveyCount(fillRequest.getId());
            log.warn("Form submission failed on attempt {} for request: {}, row: {}", attempt,
                fillRequest.getId(), task.getRowIndex());

            // Wait before retry
            if (attempt < 3) {
              Thread.sleep(2000 * attempt); // Progressive delay: 2s, 4s
            }
          }
        } catch (Exception e) {
          log.error("Exception on form submission attempt {} for request: {}, row: {}: {}", attempt,
              fillRequest.getId(), task.getRowIndex(), e.getMessage());

          if (attempt < 3) {
            Thread.sleep(2000 * attempt);
          }
        }
      }

      if (success) {
        log.info("Form submission successful for request: {}, row: {}", fillRequest.getId(),
            task.getRowIndex());
        try {
          // Use simple atomic increment - this was working fine before
          boolean incrementSuccess =
              fillRequestCounterService.incrementCompletedSurvey(fillRequest.getId());
          if (!incrementSuccess) {
            log.warn(
                "Failed to increment completedSurvey for {} after retries (may have reached limit)",
                fillRequest.getId());
          }
          // Emit progress update after successful increment
          emitProgressUpdate(fillRequest.getId());
        } catch (Exception e) {
          log.error("Failed to increment completedSurvey for {}: {}", fillRequest.getId(),
              e.getMessage());
        }
      } else {
        // Increment failed survey count in database
        incrementFailedSurveyCount(fillRequest.getId());
        log.error("Form submission failed for request: {}, row: {}", fillRequest.getId(),
            task.getRowIndex());

        // Increment failed survey count in database
        incrementFailedSurveyCount(fillRequest.getId());
      }

      return success;

    } catch (Exception e) {
      log.error("Error executing form fill for request: {}, row: {}", fillRequest.getId(),
          task.getRowIndex(), e);
      return false;
    }
  }

  /**
   * Build form data from sheet row based on mappings
   */
  private Map<String, String> buildFormData(DataFillRequestDTO originalRequest,
      Map<UUID, Question> questionMap, Map<String, Object> rowData, FillRequest fillRequest) {

    Map<String, String> formData = new java.util.HashMap<>();

    // Map each question to its corresponding column value
    for (var mapping : originalRequest.getMappings()) {
      String questionIdRaw = mapping.getQuestionId();
      String columnName = extractColumnName(mapping.getColumnName());

      // Support grid mapping keys in format "<questionId>:<rowLabel>"
      String baseQuestionId = questionIdRaw;
      String explicitRowLabel = null;
      if (questionIdRaw != null && questionIdRaw.contains(":")) {
        String[] parts = questionIdRaw.split(":", 2);
        baseQuestionId = parts[0];
        explicitRowLabel = parts.length > 1 ? parts[1] : null;
      }

      // Find question
      Question question = questionMap.get(UUID.fromString(baseQuestionId));
      if (question == null) {
        log.warn("Question not found for ID: {}", baseQuestionId);
        continue;
      }

      // Get value from sheet
      Object value = rowData.get(columnName);
      if (value == null) {
        log.warn("No value found in column {} for question {}", columnName, baseQuestionId);
        continue;
      }

      // Convert value based on question type
      String convertedValue = convertValueBasedOnQuestionType(value.toString(), question,
          explicitRowLabel, columnName, fillRequest);
      if (convertedValue != null) {
        // For grid questions, accumulate multiple rows into a single entry using ';'
        String type = question.getType() == null ? "" : question.getType().toLowerCase();
        if ("multiple_choice_grid".equals(type) || "checkbox_grid".equals(type)) {
          String existing = formData.get(baseQuestionId);
          if (existing != null && !existing.isBlank()) {
            formData.put(baseQuestionId, existing + ";" + convertedValue);
          } else {
            formData.put(baseQuestionId, convertedValue);
          }
        } else {
          formData.put(baseQuestionId, convertedValue);
        }
      } else {
        log.warn("Failed to convert value '{}' for question '{}' (ID: {})", value,
            question.getTitle(), baseQuestionId);
      }
    }

    return formData;
  }

  /**
   * Convert value based on question type
   */
  private String convertValueBasedOnQuestionType(String value, Question question,
      String explicitRowLabel, String columnName, FillRequest fillRequest) {
    try {
      switch (question.getType().toLowerCase()) {
        case "text":
        case "paragraph":
          return value;

        case "radio":
        case "checkbox":
        case "combobox":
        case "dropdown":
        case "select": {
          String raw = value == null ? "" : value.trim();
          int dashIdx = raw.lastIndexOf('-');
          String main = dashIdx > 0 ? raw.substring(0, dashIdx).trim() : raw;
          String other = dashIdx > 0 ? raw.substring(dashIdx + 1).trim() : null;

          // Check if question has __other_option__
          boolean hasOtherOption = getQuestionOptionsSafely(question).stream().anyMatch(
              opt -> opt.getValue() != null && "__other_option__".equalsIgnoreCase(opt.getValue()));

          // Support multi-select encoding for checkbox with '|' only
          if ("checkbox".equalsIgnoreCase(question.getType())
              || "multiselect".equalsIgnoreCase(question.getType())) {
            if (main.contains("|")) {
              String converted = dataFillValidator.convertMultiplePositionsToValues(main,
                  getQuestionOptionsSafely(question));
              return (other != null && !other.isEmpty()) ? converted + "-" + other : converted;
            }
          }

          // Check if this is an "other" option with custom text (format: "7-text123")
          if (hasOtherOption && other != null && !other.isEmpty()) {
            log.debug("Processing potential 'other' option: main='{}', other='{}' for question {}",
                main, other, question.getId());

            // This is likely an "other" option with custom text
            // Check if main part is a number (position) that corresponds to the "other" option
            if (main.matches("\\d+")) {
              try {
                int position = Integer.parseInt(main);
                // Find the "__other_option__" option and check if its position matches
                QuestionOption otherOption = getQuestionOptionsSafely(question).stream()
                    .filter(opt -> opt.getValue() != null
                        && "__other_option__".equalsIgnoreCase(opt.getValue()))
                    .findFirst().orElse(null);

                if (otherOption != null && otherOption.getPosition() != null
                    && otherOption.getPosition() == position) {
                  // This is indeed an "other" option with custom text
                  log.debug(
                      "Converting 'other' option with matching position {} and text '{}' for question {}",
                      position, other, question.getId());
                  return "__other_option__" + "-" + other;
                } else {
                  // Increment failed survey count in database
                  incrementFailedSurveyCount(fillRequest.getId());
                  // If position doesn't match, but this is the only option with text after dash,
                  // and the text doesn't match any predefined option, treat it as "other" text
                  boolean isOptionValue = getQuestionOptionsSafely(question).stream()
                      .anyMatch(opt -> opt.getValue() != null && other.equals(opt.getValue()));

                  if (!isOptionValue) {
                    log.debug(
                        "Converting 'other' option with non-matching position {} and text '{}' for question {} (text not in predefined options)",
                        position, other, question.getId());
                    return "__other_option__" + "-" + other;
                  } else {
                    // Increment failed survey count in database
                    incrementFailedSurveyCount(fillRequest.getId());
                    log.debug(
                        "Text '{}' matches predefined option, not treating as 'other' for question {}",
                        other, question.getId());
                  }
                }
              } catch (NumberFormatException e) {
                log.debug("Invalid position number '{}' for question {}", main, question.getId());
              }
            }
          }

          // If question has __other_option__ and this is not a numeric position, treat as custom
          // text for other option
          if (hasOtherOption && !main.matches("\\d+")) {
            // For custom text in __other_option__, convert to the __other_option__ value with
            // custom text
            return "__other_option__" + (main.isEmpty() ? "" : "-" + main);
          }

          // Single numeric position
          if (main.matches("\\d+")) {
            String converted =
                dataFillValidator.convertPositionToValue(main, getQuestionOptionsSafely(question));
            return (other != null && !other.isEmpty()) ? converted + "-" + other : converted;
          }

          // Already explicit value(s); keep optional -other suffix
          return raw;
        }

        case "multiple_choice_grid": {
          String rowLabelMC = null;

          // First try explicit row label from mapping
          if (explicitRowLabel != null) {
            rowLabelMC = explicitRowLabel;
            log.debug("Using explicit row label '{}' for multiple_choice_grid in column {}",
                rowLabelMC, columnName);
          }
          // Then try to extract from column name format like "Title [Row Label]"
          else if (extractBracketLabel(columnName) != null) {
            rowLabelMC = extractBracketLabel(columnName);
            log.debug("Extracted row label '{}' from column name '{}'", rowLabelMC, columnName);
          }
          // Then try to map column name to row by position (GN1 = row 1, GN2 = row 2, etc.)
          else if (isColumnNameMappableToRow(columnName)) {
            rowLabelMC = mapColumnNameToRowLabel(question, columnName);
            if (rowLabelMC != null) {
              log.debug("Mapped column '{}' to row label '{}' for question '{}'", columnName,
                  rowLabelMC, question.getTitle());
            }
          }
          // Finally try to get row label by position from column name (Q9.4 -> position 4)
          if (rowLabelMC == null) {
            int position = extractPositionFromColumnName(columnName);
            if (position > 0) {
              rowLabelMC = getRowLabelByPosition(question, position);
              if (rowLabelMC != null) {
                log.info(
                    "Using row label by position {} '{}' for multiple_choice_grid in column {}",
                    position, rowLabelMC, columnName);
              }
            }
            // Fallback to first row if position extraction fails
            if (rowLabelMC == null) {
              rowLabelMC = getFirstRowLabelFromQuestion(question);
              if (rowLabelMC != null) {
                log.info("Using fallback row label '{}' for multiple_choice_grid in column {}",
                    rowLabelMC, columnName);
              }
            }
          }

          if (rowLabelMC == null) {
            log.warn(
                "Missing row label for multiple_choice_grid in column {} and no fallback row found",
                columnName);
            return null;
          }
          String v = value.trim();
          // Map numeric index (1-based) to column option value
          if (v.matches("\\d+")) {
            List<QuestionOption> columns =
                getQuestionOptionsSafely(question).stream().filter(o -> !o.isRow())
                    .sorted((a, b) -> Integer.compare(a.getPosition() == null ? 0 : a.getPosition(),
                        b.getPosition() == null ? 0 : b.getPosition()))
                    .toList();
            int pos = Integer.parseInt(v);
            if (pos >= 1 && pos <= columns.size()) {
              v = columns.get(pos - 1).getValue();
            }
          }
          return rowLabelMC + ":" + v;
        }

        case "checkbox_grid": {
          String rowLabelCB = null;

          // First try explicit row label from mapping
          if (explicitRowLabel != null) {
            rowLabelCB = explicitRowLabel;
            log.debug("Using explicit row label '{}' for checkbox_grid in column {}", rowLabelCB,
                columnName);
          }
          // Then try to extract from column name format like "Title [Row Label]"
          else if (extractBracketLabel(columnName) != null) {
            rowLabelCB = extractBracketLabel(columnName);
            log.debug("Extracted row label '{}' from column name '{}'", rowLabelCB, columnName);
          }
          // Then try to map column name to row by position (GN1 = row 1, GN2 = row 2, etc.)
          else if (isColumnNameMappableToRow(columnName)) {
            rowLabelCB = mapColumnNameToRowLabel(question, columnName);
            if (rowLabelCB != null) {
              log.debug("Mapped column '{}' to row label '{}' for question '{}'", columnName,
                  rowLabelCB, question.getTitle());
            }
          }
          // Finally try to get row label by position from column name (Q9.4 -> position 4)
          if (rowLabelCB == null) {
            int position = extractPositionFromColumnName(columnName);
            if (position > 0) {
              rowLabelCB = getRowLabelByPosition(question, position);
              if (rowLabelCB != null) {
                log.info("Using row label by position {} '{}' for checkbox_grid in column {}",
                    position, rowLabelCB, columnName);
              }
            }
            // Fallback to first row if position extraction fails
            if (rowLabelCB == null) {
              rowLabelCB = getFirstRowLabelFromQuestion(question);
              if (rowLabelCB != null) {
                log.info("Using fallback row label '{}' for checkbox_grid in column {}", rowLabelCB,
                    columnName);
              }
            }
          }

          if (rowLabelCB == null) {
            log.warn("Missing row label for checkbox_grid in column {} and no fallback row found",
                columnName);
            return null;
          }
          String[] parts = value.split("\\|");
          List<QuestionOption> columns =
              getQuestionOptionsSafely(question).stream().filter(o -> !o.isRow())
                  .sorted((a, b) -> Integer.compare(a.getPosition() == null ? 0 : a.getPosition(),
                      b.getPosition() == null ? 0 : b.getPosition()))
                  .toList();
          java.util.List<String> mapped = new java.util.ArrayList<>();
          for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty())
              continue;
            if (t.matches("\\d+")) {
              int pos = Integer.parseInt(t);
              if (pos >= 1 && pos <= columns.size()) {
                mapped.add(columns.get(pos - 1).getValue());
              } else {
                // Increment failed survey count in database
                incrementFailedSurveyCount(fillRequest.getId());
                mapped.add(t);
              }
            } else {
              // Increment failed survey count in database
              incrementFailedSurveyCount(fillRequest.getId());
              mapped.add(t);
            }
          }
          String joined = String.join("|", mapped);
          return rowLabelCB + ":" + joined;
        }

        default:
          log.error("Unsupported question type: {} for question ID: {}", question.getType(),
              question.getId());
          return null;
      }
    } catch (Exception e) {
      log.error("Error converting value for question {}: {}", question.getId(), e.getMessage());
      return null;
    }
  }

  /**
   * Extract column name from formatted string "A - Column Name"
   */
  private String extractColumnName(String formattedColumnName) {
    if (formattedColumnName.contains(" - ")) {
      return formattedColumnName.substring(formattedColumnName.indexOf(" - ") + 3);
    }
    return formattedColumnName;
  }

  // Extract label inside brackets from formatted column name: "Title [Label]" -> "Label"
  private String extractBracketLabel(String formattedColumnName) {
    if (formattedColumnName == null) {
      return null;
    }
    int start = formattedColumnName.lastIndexOf('[');
    int end = formattedColumnName.lastIndexOf(']');
    if (start >= 0 && end > start) {
      return formattedColumnName.substring(start + 1, end).trim();
    }
    return null;
  }

  /**
   * Get the first row label from a grid question's options
   */
  private String getFirstRowLabelFromQuestion(Question question) {
    try {
      List<QuestionOption> options = getQuestionOptionsSafely(question);

      // Sắp xếp theo position để đảm bảo lấy đúng row đầu tiên
      List<QuestionOption> rowOptions = options.stream()
          .filter(option -> option.isRow() && option.getText() != null
              && !option.getText().trim().isEmpty())
          .sorted((a, b) -> Integer.compare(a.getPosition() == null ? 0 : a.getPosition(),
              b.getPosition() == null ? 0 : b.getPosition()))
          .collect(Collectors.toList());

      return rowOptions.isEmpty() ? null : rowOptions.get(0).getText().trim();
    } catch (Exception e) {
      log.debug("Error getting first row label for question '{}': {}", question.getTitle(),
          e.getMessage());
    }
    return null;
  }

  /**
   * Get row label by position for grid questions
   */
  private String getRowLabelByPosition(Question question, int position) {
    try {
      List<QuestionOption> options = getQuestionOptionsSafely(question);

      List<QuestionOption> rowOptions = options.stream()
          .filter(option -> option.isRow() && option.getText() != null
              && !option.getText().trim().isEmpty())
          .sorted((a, b) -> Integer.compare(a.getPosition() == null ? 0 : a.getPosition(),
              b.getPosition() == null ? 0 : b.getPosition()))
          .collect(Collectors.toList());

      if (position >= 1 && position <= rowOptions.size()) {
        return rowOptions.get(position - 1).getText().trim();
      }
    } catch (Exception e) {
      log.debug("Error getting row label by position {} for question '{}': {}", position,
          question.getTitle(), e.getMessage());
    }
    return null;
  }

  /**
   * Extract position number from column name (e.g., Q9.4 -> 4, Q14.4 -> 4)
   */
  private int extractPositionFromColumnName(String columnName) {
    if (columnName == null || columnName.trim().isEmpty()) {
      return 0;
    }

    try {
      // Extract number after the last dot (e.g., Q9.4 -> 4)
      String trimmed = columnName.trim();
      int lastDotIndex = trimmed.lastIndexOf('.');
      if (lastDotIndex >= 0 && lastDotIndex < trimmed.length() - 1) {
        String numberPart = trimmed.substring(lastDotIndex + 1);
        // Remove any non-numeric characters (like "(T)" in "Q9.4 (T)")
        numberPart = numberPart.replaceAll("[^0-9]", "");
        if (!numberPart.isEmpty()) {
          return Integer.parseInt(numberPart);
        }
      }
    } catch (Exception e) {
      log.debug("Error extracting position from column name '{}': {}", columnName, e.getMessage());
    }

    return 0;
  }

  /**
   * Safely get question options, handling lazy initialization
   */
  private List<QuestionOption> getQuestionOptionsSafely(Question question) {
    try {
      return question.getOptions() == null ? new ArrayList<>() : question.getOptions();
    } catch (org.hibernate.LazyInitializationException e) {
      log.debug("Lazy initialization exception for question options, reloading question: {}",
          question.getId());
      try {
        Question reloadedQuestion =
            questionRepository.findWithOptionsById(question.getId()).orElse(question);
        return reloadedQuestion.getOptions() == null ? new ArrayList<>()
            : reloadedQuestion.getOptions();
      } catch (Exception ex) {
        log.warn("Failed to reload question with options: {}", question.getId(), ex);
        return new ArrayList<>();
      }
    }
  }

  /**
   * Check if column name can be mapped to a row by position (e.g., GN1, GN2, etc.)
   */
  private boolean isColumnNameMappableToRow(String columnName) {
    if (columnName == null || columnName.trim().isEmpty()) {
      return false;
    }

    // Check if column name follows pattern like GN1, GN2, GN3, etc.
    // or any pattern that ends with a number
    String trimmed = columnName.trim();
    return trimmed.matches(".*\\d+$");
  }

  /**
   * Map column name to row label by position (GN1 = row 1, GN2 = row 2, etc.)
   */
  private String mapColumnNameToRowLabel(Question question, String columnName) {
    try {
      if (columnName == null || columnName.trim().isEmpty()) {
        return null;
      }

      // Extract the number from column name (e.g., GN1 -> 1, GN2 -> 2)
      String trimmed = columnName.trim();
      String numberPart = trimmed.replaceAll(".*?(\\d+)$", "$1");

      if (numberPart.isEmpty()) {
        return null;
      }

      int rowIndex = Integer.parseInt(numberPart);
      if (rowIndex < 1) {
        return null;
      }

      // Get all row options and find the one at the specified position
      List<QuestionOption> rowOptions =
          getQuestionOptionsSafely(question).stream().filter(opt -> opt.isRow())
              .sorted((a, b) -> Integer.compare(a.getPosition() == null ? 0 : a.getPosition(),
                  b.getPosition() == null ? 0 : b.getPosition()))
              .collect(Collectors.toList());

      if (rowIndex <= rowOptions.size()) {
        QuestionOption targetRow = rowOptions.get(rowIndex - 1);
        if (targetRow.getText() != null && !targetRow.getText().trim().isEmpty()) {
          return targetRow.getText().trim();
        }
      }

      log.debug("Could not map column '{}' to row label for question '{}'", columnName,
          question.getTitle());
      return null;

    } catch (Exception e) {
      log.debug("Error mapping column '{}' to row label for question '{}': {}", columnName,
          question.getTitle(), e.getMessage());
      return null;
    }
  }

  /**
   * Determine whether to use executionTime or delaySeconds based on task density
   */
  private boolean shouldUseExecutionTime(LocalDateTime start, LocalDateTime end, int taskCount) {
    if (start == null || end == null || !end.isAfter(start)) {
      return false; // Use delaySeconds for same-day or invalid date ranges
    }

    long hours = java.time.Duration.between(start, end).toHours();
    if (hours <= 0) {
      return false;
    }

    double density = (double) taskCount / hours;
    boolean useExecTime = density < 30.0; // Threshold: < 30 submissions per hour

    log.info("Task density analysis: {} tasks over {} hours = {} tasks/hour, using {}", taskCount,
        hours, String.format("%.1f", density),
        useExecTime ? "executionTime (slot-based)" : "delaySeconds (2-15min intervals)");

    return useExecTime;
  }
}

