package com.dienform.tool.dienformtudong.fillrequest.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
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

  private ExecutorService executorService;

  @Autowired
  private com.dienform.realtime.FillRequestRealtimeGateway realtimeGateway;

  @Autowired
  private CurrentUserUtil currentUserUtil;

  @PostConstruct
  public void init() {
    log.info("DataFillCampaignService: Initializing executor service with thread pool size: {}",
        threadPoolSize);
    executorService = Executors.newFixedThreadPool(threadPoolSize);
    log.info("DataFillCampaignService: Executor service initialized successfully with {} threads",
        threadPoolSize);
  }

  @PreDestroy
  public void cleanup() {
    shutdownExecutorService();
  }

  /**
   * Execute data fill campaign based on schedule
   */
  public CompletableFuture<Void> executeCampaign(FillRequest fillRequest,
      DataFillRequestDTO originalRequest, List<Question> questions, List<ScheduledTask> schedule) {

    log.info("Starting data fill campaign for request: {} with {} tasks using {} threads",
        fillRequest.getId(), schedule.size(), threadPoolSize);

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

      // Check if there are enough data rows for the requested survey count
      if (sheetData.size() < fillRequest.getSurveyCount()) {
        log.warn(
            "Not enough data rows in sheet. Required: {}, Available: {}. Data will be reused from the beginning.",
            fillRequest.getSurveyCount(), sheetData.size());
      }

      AtomicInteger completedTasks = new AtomicInteger(0);
      AtomicInteger successfulTasks = new AtomicInteger(0);
      AtomicInteger submittedTasks = new AtomicInteger(0);
      int totalTasks = schedule.size();

      log.info("TASK EXECUTION PLAN: Total {} tasks will be processed with {} threads", totalTasks,
          threadPoolSize);
      log.info(
          "EXPECTED FLOW: First {} tasks start immediately, remaining tasks queue and wait for thread availability",
          Math.min(totalTasks, threadPoolSize));

      // Execute each task
      log.info("Submitting {} tasks to executor service for fillRequest: {} (thread pool size: {})",
          schedule.size(), fillRequest.getId(), threadPoolSize);

      for (int i = 0; i < schedule.size(); i++) {
        ScheduledTask task = schedule.get(i);
        final int taskIndex = i + 1;
        int submitted = submittedTasks.incrementAndGet();
        log.info("Submitting task {}/{} (row {}) for fillRequest: {} - SUBMITTED: {}/{}", taskIndex,
            schedule.size(), task.getRowIndex(), fillRequest.getId(), submitted, totalTasks);

        // Add small delay between task submissions for low-spec machines
        if (i > 0) {
          try {
            Thread.sleep(500); // 500ms delay between submissions
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Task submission delay interrupted for fillRequest: {}", fillRequest.getId());
          }
        }

        // Monitor thread pool status
        logThreadPoolStatus("After submitting task " + taskIndex);

        scheduleFormFill(fillRequest, originalRequest, questionMap, sheetData, task)
            .thenAccept(success -> {
              int completed = completedTasks.incrementAndGet();
              if (success) {
                successfulTasks.incrementAndGet();
                log.info("Task completed successfully: {}/{} for fillRequest: {}", completed,
                    totalTasks, fillRequest.getId());
              } else {
                log.warn("Task failed: {}/{} for fillRequest: {}", completed, totalTasks,
                    fillRequest.getId());
              }

              // Check if all tasks are complete
              if (completed == totalTasks) {
                log.info("All tasks completed for campaign {}. Successful: {}/{}",
                    fillRequest.getId(), successfulTasks.get(), totalTasks);

                // Decide final status based on persisted completedSurvey to ensure accuracy
                FillRequest fresh =
                    fillRequestRepository.findById(fillRequest.getId()).orElse(null);
                if (fresh == null) {
                  updateFillRequestStatus(fillRequest, FillRequestStatusEnum.FAILED);
                  executionFuture.complete(null);
                  return;
                }

                FillRequestStatusEnum finalStatus;
                if (fresh.getCompletedSurvey() >= fresh.getSurveyCount()
                    && successfulTasks.get() == totalTasks) {
                  finalStatus = FillRequestStatusEnum.COMPLETED;
                } else if (successfulTasks.get() == 0 || successfulTasks.get() < totalTasks) {
                  finalStatus = FillRequestStatusEnum.FAILED;
                } else {
                  // Not all persisted yet, keep IN_PROCESS; caller may check later
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

                  // Get current user ID if available
                  String userId = null;
                  try {
                    userId = currentUserUtil.getCurrentUserIdIfPresent().map(UUID::toString)
                        .orElse(null);
                  } catch (Exception ignore) {
                    log.debug("Failed to get current user ID: {}", ignore.getMessage());
                  }

                  // Use centralized emit method with deduplication
                  realtimeGateway.emitUpdateWithUser(fresh.getForm().getId().toString(), evt,
                      userId);
                } catch (Exception ignore) {
                  log.debug("Failed to emit final status update: {}", ignore.getMessage());
                }

                executionFuture.complete(null);
              }
            }).exceptionally(throwable -> {
              log.error("Task execution failed", throwable);
              if (completedTasks.incrementAndGet() == totalTasks) {
                updateFillRequestStatus(fillRequest, FillRequestStatusEnum.FAILED);
                executionFuture.complete(null);
              }
              return null;
            });
      }

      // Validate all tasks were submitted
      int finalSubmittedCount = submittedTasks.get();
      if (finalSubmittedCount != totalTasks) {
        log.error(
            "CRITICAL: Only {}/{} tasks were submitted! Missing tasks detected for fillRequest: {}",
            finalSubmittedCount, totalTasks, fillRequest.getId());
      } else {
        log.info("SUCCESS: All {}/{} tasks submitted to executor queue for fillRequest: {}",
            finalSubmittedCount, totalTasks, fillRequest.getId());
      }

    } catch (Exception e) {
      log.error("Failed to initialize campaign: {}", fillRequest.getId(), e);
      updateFillRequestStatus(fillRequest, FillRequestStatusEnum.FAILED);
      executionFuture.complete(null);
    }

    return executionFuture;
  }

  @Transactional
  protected void updateFillRequestStatus(FillRequest fillRequest, FillRequestStatusEnum newStatus) {
    try {
      FillRequest freshRequest = fillRequestRepository.findById(fillRequest.getId()).orElseThrow(
          () -> new RuntimeException("Fill request not found: " + fillRequest.getId()));

      if (newStatus != freshRequest.getStatus()) {
        freshRequest.setStatus(newStatus);
        fillRequestRepository.save(freshRequest);
        log.info("Updated fill request {} status to: {}", fillRequest.getId(), newStatus.name());
        // Emit realtime update for any status change
        try {
          com.dienform.realtime.dto.FillRequestUpdateEvent evt =
              com.dienform.realtime.dto.FillRequestUpdateEvent.builder()
                  .formId(freshRequest.getForm().getId().toString())
                  .requestId(freshRequest.getId().toString()).status(newStatus.name())
                  .completedSurvey(freshRequest.getCompletedSurvey())
                  .surveyCount(freshRequest.getSurveyCount())
                  .updatedAt(java.time.Instant.now().toString()).build();
          realtimeGateway.emitUpdate(freshRequest.getForm().getId().toString(), evt);
        } catch (Exception ignore) {
          log.debug("Failed to emit status update: {}", ignore.getMessage());
        }
      } else {
        log.debug("Fill request {} status unchanged: {}", fillRequest.getId(), newStatus.name());
      }
    } catch (Exception e) {
      log.error("Failed to update fill request status: {}", fillRequest.getId(), e);
    }
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

  /**
   * Emit progress update to both form room and user-specific room
   */
  private void emitProgressUpdate(UUID fillRequestId) {
    try {
      com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest current =
          fillRequestRepository.findById(fillRequestId).orElse(null);
      if (current != null && current.getForm() != null) {
        // Only emit if there's meaningful progress (completedSurvey > 0)
        if (current.getCompletedSurvey() > 0) {
          com.dienform.realtime.dto.FillRequestUpdateEvent evt =
              com.dienform.realtime.dto.FillRequestUpdateEvent.builder()
                  .formId(current.getForm().getId().toString())
                  .requestId(current.getId().toString())
                  .status(current.getStatus() == null
                      ? com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum.IN_PROCESS
                          .name()
                      : current.getStatus().name())
                  .completedSurvey(current.getCompletedSurvey())
                  .surveyCount(current.getSurveyCount())
                  .updatedAt(java.time.Instant.now().toString()).build();

          // Emit to form room only (avoid duplicate user-specific emissions)
          realtimeGateway.emitUpdate(current.getForm().getId().toString(), evt);

          log.debug("Emitted progress update for fillRequest: {} - {}/{}", fillRequestId,
              current.getCompletedSurvey(), current.getSurveyCount());
        }
      }
    } catch (Exception e) {
      log.warn("Failed to emit progress update: {}", e.getMessage());
    }
  }

  private void shutdownExecutorService() {
    if (executorService != null) {
      executorService.shutdown();
      try {
        if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
          executorService.shutdownNow();
        }
      } catch (InterruptedException e) {
        executorService.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }

  private CompletableFuture<Boolean> scheduleFormFill(FillRequest fillRequest,
      DataFillRequestDTO originalRequest, Map<UUID, Question> questionMap,
      List<Map<String, Object>> sheetData, ScheduledTask task) {

    log.info(
        "Scheduling form fill for task row {} (fillRequest: {}) - Submitting to executor queue",
        task.getRowIndex(), fillRequest.getId());

    CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
      try {
        log.info("THREAD ASSIGNED: Task row {} started execution on thread: {} (fillRequest: {})",
            task.getRowIndex(), Thread.currentThread().getName(), fillRequest.getId());

        // Calculate delay
        long delaySeconds = Math.max(0,
            Duration.between(LocalDateTime.now(), task.getExecutionTime()).getSeconds());

        if (delaySeconds > 0) {
          log.info("Waiting {} seconds before executing task for row {} on thread: {}",
              delaySeconds, task.getRowIndex(), Thread.currentThread().getName());
          Thread.sleep(delaySeconds * 1000);
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
      }
    }, executorService);

    // Add timeout mechanism to prevent hanging tasks (reduced for low-spec machines)
    CompletableFuture<Boolean> timeoutFuture =
        future.orTimeout(180, TimeUnit.SECONDS).exceptionally(throwable -> {
          if (throwable instanceof java.util.concurrent.TimeoutException) {
            log.error("Task for row {} timed out after 180 seconds (fillRequest: {})",
                task.getRowIndex(), fillRequest.getId());
          } else {
            log.error("Task for row {} failed with exception (fillRequest: {}): {}",
                task.getRowIndex(), fillRequest.getId(), throwable.getMessage());
          }
          return false;
        });

    log.debug("Scheduled task for row {} with timeout protection (fillRequest: {})",
        task.getRowIndex(), fillRequest.getId());

    return timeoutFuture;
  }

  /**
   * Log thread pool status for debugging
   */
  private void logThreadPoolStatus(String context) {
    if (executorService instanceof ThreadPoolExecutor) {
      ThreadPoolExecutor threadPool = (ThreadPoolExecutor) executorService;
      log.debug("THREAD POOL STATUS [{}]: Active={}, Pool={}, Queue={}, Completed={}", context,
          threadPool.getActiveCount(), threadPool.getPoolSize(), threadPool.getQueue().size(),
          threadPool.getCompletedTaskCount());
    }
  }

  /**
   * Execute actual form filling with data from sheet row
   */
  private boolean executeFormFill(FillRequest fillRequest, DataFillRequestDTO originalRequest,
      Map<UUID, Question> questionMap, List<Map<String, Object>> sheetData, ScheduledTask task) {

    log.info("Executing form fill for request: {}, row: {}", fillRequest.getId(),
        task.getRowIndex());

    try {
      // Get data for this row (wrap if not enough rows)
      int actualRowIndex = task.getRowIndex() % sheetData.size();
      Map<String, Object> rowData = sheetData.get(actualRowIndex);
      log.info("Using data from row {} (wrapped from row {})", actualRowIndex, task.getRowIndex());

      // Build form submission data
      Map<String, String> formData = buildFormData(originalRequest, questionMap, rowData);

      // Add human-like delay before submission
      if (task.getDelaySeconds() > 0) {
        Thread.sleep(task.getDelaySeconds() * 1000L);
      }

      // Submit form using browser automation
      String formUrl = fillRequest.getForm().getEditLink();
      boolean success = googleFormService.submitFormWithBrowser(fillRequest.getId(),
          fillRequest.getForm().getId(), formUrl, formData);

      if (success) {
        log.info("Form submission successful for request: {}, row: {}", fillRequest.getId(),
            task.getRowIndex());
        try {
          // Use dedicated counter service with REQUIRES_NEW transaction and retry logic
          boolean incrementSuccess =
              fillRequestCounterService.incrementCompletedSurveyWithDelay(fillRequest.getId());
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
        log.error("Form submission failed for request: {}, row: {}", fillRequest.getId(),
            task.getRowIndex());
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
      Map<UUID, Question> questionMap, Map<String, Object> rowData) {

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
      String convertedValue =
          convertValueBasedOnQuestionType(value.toString(), question, explicitRowLabel, columnName);
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
      }
    }

    return formData;
  }

  /**
   * Convert value based on question type
   */
  private String convertValueBasedOnQuestionType(String value, Question question,
      String explicitRowLabel, String columnName) {
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
                      "Converting 'other' option with position {} and text '{}' for question {}",
                      position, other, question.getId());
                  return "__other_option__" + "-" + other;
                }
              } catch (NumberFormatException e) {
                // Ignore if position is not a valid number
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
          String rowLabelMC =
              explicitRowLabel != null ? explicitRowLabel : extractBracketLabel(columnName);
          if (rowLabelMC == null) {
            log.warn("Missing row label for multiple_choice_grid in column {}", columnName);
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
          String rowLabelCB =
              explicitRowLabel != null ? explicitRowLabel : extractBracketLabel(columnName);
          if (rowLabelCB == null) {
            log.warn("Missing row label for checkbox_grid in column {}", columnName);
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
                mapped.add(t);
              }
            } else {
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
}

