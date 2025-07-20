package com.dienform.tool.dienformtudong.fillrequest.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.common.util.Constants;
import com.dienform.common.util.CopyUtil;
import com.dienform.tool.dienformtudong.datamapping.dto.request.DataFillRequestDTO;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestRepository;
import com.dienform.tool.dienformtudong.fillrequest.service.ScheduleDistributionService.ScheduledTask;
import com.dienform.tool.dienformtudong.fillrequest.validator.DataFillValidator;
import com.dienform.tool.dienformtudong.googleform.service.GoogleFormService;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.entity.QuestionOption;
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

  @Value("${google.form.thread-pool-size:1}")
  private int threadPoolSize;

  private ExecutorService executorService;

  @PostConstruct
  public void init() {
    log.info("Initializing executor service with thread pool size: {}", threadPoolSize);
    executorService = Executors.newFixedThreadPool(threadPoolSize);
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

    log.info("Starting data fill campaign for request: {} with {} tasks", fillRequest.getId(),
        schedule.size());

    // Set initial status to RUNNING
    updateFillRequestStatus(fillRequest, Constants.FILL_REQUEST_STATUS_RUNNING);

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
        updateFillRequestStatus(fillRequest, Constants.FILL_REQUEST_STATUS_FAILED);
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
      int totalTasks = schedule.size();

      // Execute each task
      for (ScheduledTask task : schedule) {
        scheduleFormFill(fillRequest, originalRequest, questionMap, sheetData, task)
            .thenAccept(success -> {
              if (success) {
                successfulTasks.incrementAndGet();
              }

              // Check if all tasks are complete
              if (completedTasks.incrementAndGet() == totalTasks) {
                log.info("All tasks completed for campaign {}. Successful: {}/{}",
                    fillRequest.getId(), successfulTasks.get(), totalTasks);

                String finalStatus =
                    successfulTasks.get() == totalTasks ? Constants.FILL_REQUEST_STATUS_COMPLETED
                        : Constants.FILL_REQUEST_STATUS_FAILED;

                updateFillRequestStatus(fillRequest, finalStatus);
                executionFuture.complete(null);
              }
            }).exceptionally(throwable -> {
              log.error("Task execution failed", throwable);
              if (completedTasks.incrementAndGet() == totalTasks) {
                updateFillRequestStatus(fillRequest, Constants.FILL_REQUEST_STATUS_FAILED);
                executionFuture.complete(null);
              }
              return null;
            });
      }

    } catch (Exception e) {
      log.error("Failed to initialize campaign: {}", fillRequest.getId(), e);
      updateFillRequestStatus(fillRequest, Constants.FILL_REQUEST_STATUS_FAILED);
      executionFuture.complete(null);
    }

    return executionFuture;
  }

  @Transactional
  protected void updateFillRequestStatus(FillRequest fillRequest, String newStatus) {
    try {
      FillRequest freshRequest = fillRequestRepository.findById(fillRequest.getId()).orElseThrow(
          () -> new RuntimeException("Fill request not found: " + fillRequest.getId()));

      if (!newStatus.equals(freshRequest.getStatus())) {
        freshRequest.setStatus(newStatus);
        fillRequestRepository.save(freshRequest);
        log.info("Updated fill request {} status to: {}", fillRequest.getId(), newStatus);
      }
    } catch (Exception e) {
      log.error("Failed to update fill request status: {}", fillRequest.getId(), e);
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

    return CompletableFuture.supplyAsync(() -> {
      try {
        // Calculate delay
        long delaySeconds = Math.max(0,
            Duration.between(LocalDateTime.now(), task.getExecutionTime()).getSeconds());

        if (delaySeconds > 0) {
          log.info("Waiting {} seconds before executing task for row {}", delaySeconds,
              task.getRowIndex());
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

        return success;
      } catch (Exception e) {
        log.error("Error in scheduled form fill: {}", e.getMessage(), e);
        return false;
      }
    }, executorService);
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
      boolean success = googleFormService.submitFormWithBrowser(formUrl, formData);

      if (success) {
        log.info("Form submission successful for request: {}, row: {}", fillRequest.getId(),
            task.getRowIndex());
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
      String questionId = mapping.getQuestionId();
      String columnName = extractColumnName(mapping.getColumnName());

      // Find question
      Question question = questionMap.get(UUID.fromString(questionId));
      if (question == null) {
        log.warn("Question not found for ID: {}", questionId);
        continue;
      }

      // Get value from sheet
      Object value = rowData.get(columnName);
      if (value == null) {
        log.warn("No value found in column {} for question {}", columnName, questionId);
        continue;
      }

      // Convert value based on question type
      String convertedValue = convertValueBasedOnQuestionType(value.toString(), question);
      if (convertedValue != null) {
        formData.put(questionId, convertedValue);
      }
    }

    return formData;
  }

  /**
   * Convert value based on question type
   */
  private String convertValueBasedOnQuestionType(String value, Question question) {
    try {
      switch (question.getType().toLowerCase()) {
        case "text":
        case "paragraph":
          return value;

        case "radio":
        case "checkbox":
        case "combobox":
        case "dropdown":
        case "select":
          // If value is numeric, treat it as position
          if (value.matches("\\d+")) {
            return dataFillValidator.convertPositionToValue(value, question.getOptions());
          }
          return value;

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
}
