package com.dienform.tool.dienformtudong.fillrequest.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.dienform.tool.dienformtudong.datamapping.dto.request.DataFillRequestDTO;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.fillrequest.service.ScheduleDistributionService.ScheduledTask;
import com.dienform.tool.dienformtudong.fillrequest.validator.DataFillValidator;
import com.dienform.tool.dienformtudong.googleform.service.GoogleFormService;
import com.dienform.tool.dienformtudong.question.entity.Question;
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

  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(10);

  /**
   * Execute data fill campaign based on schedule
   */
  public CompletableFuture<Void> executeCampaign(FillRequest fillRequest,
      DataFillRequestDTO originalRequest, List<Question> questions, List<ScheduledTask> schedule) {

    log.info("Starting data fill campaign for request: {} with {} tasks", fillRequest.getId(),
        schedule.size());

    return CompletableFuture.runAsync(() -> {
      try {
        // Read sheet data
        List<Map<String, Object>> sheetData =
            googleSheetsService.getSheetData(originalRequest.getSheetLink());

        // Execute each scheduled task
        for (ScheduledTask task : schedule) {
          scheduleFormFill(fillRequest, originalRequest, questions, sheetData, task);
        }

        log.info("All tasks scheduled for campaign: {}", fillRequest.getId());

      } catch (Exception e) {
        log.error("Failed to execute campaign: {}", fillRequest.getId(), e);
        throw new RuntimeException("Campaign execution failed", e);
      }
    });
  }

  /**
   * Shutdown scheduler gracefully
   */
  public void shutdown() {
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
      }
    } catch (InterruptedException e) {
      scheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Schedule individual form fill task
   */
  private void scheduleFormFill(FillRequest fillRequest, DataFillRequestDTO originalRequest,
      List<Question> questions, List<Map<String, Object>> sheetData, ScheduledTask task) {

    LocalDateTime now = LocalDateTime.now();
    LocalDateTime executionTime = task.getExecutionTime();

    long delaySeconds = java.time.Duration.between(now, executionTime).getSeconds();
    delaySeconds = Math.max(0, delaySeconds); // Don't allow negative delays

    log.debug("Scheduling task for row {} to execute in {} seconds", task.getRowIndex(),
        delaySeconds);

    scheduler.schedule(() -> {
      try {
        executeFormFill(fillRequest, originalRequest, questions, sheetData, task);
      } catch (Exception e) {
        log.error("Failed to execute form fill for task: {}", task.getRowIndex(), e);
      }
    }, delaySeconds, TimeUnit.SECONDS);
  }

  /**
   * Execute actual form filling with data from sheet row
   */
  private void executeFormFill(FillRequest fillRequest, DataFillRequestDTO originalRequest,
      List<Question> questions, List<Map<String, Object>> sheetData, ScheduledTask task) {

    log.info("Executing form fill for request: {}, row: {}", fillRequest.getId(),
        task.getRowIndex());

    try {
      // Get data for this row
      if (task.getRowIndex() >= sheetData.size()) {
        log.warn("Row index {} exceeds available data size {}", task.getRowIndex(),
            sheetData.size());
        return;
      }

      Map<String, Object> rowData = sheetData.get(task.getRowIndex());

      // Build form submission data
      Map<String, String> formData = buildFormData(originalRequest, questions, rowData);

      // Add human-like delay before submission
      if (task.getDelaySeconds() > 0) {
        Thread.sleep(task.getDelaySeconds() * 1000L);
      }

      // Submit form (you'll need to implement this based on your form submission logic)
      boolean success = submitFormData(fillRequest.getForm().getEditLink(), formData);

      log.info("Form submission {} for request: {}, row: {}", success ? "successful" : "failed",
          fillRequest.getId(), task.getRowIndex());

    } catch (Exception e) {
      log.error("Error executing form fill for request: {}, row: {}", fillRequest.getId(),
          task.getRowIndex(), e);
    }
  }

  /**
   * Build form data from sheet row based on mappings Now supports position-based selection for
   * choice questions
   */
  private Map<String, String> buildFormData(DataFillRequestDTO originalRequest,
      List<Question> questions, Map<String, Object> rowData) {

    Map<String, String> formData = new java.util.HashMap<>();

    // Map each question to its corresponding column value
    for (var mapping : originalRequest.getMappings()) {
      String questionId = mapping.getQuestionId();
      String columnName = extractColumnName(mapping.getColumnName());

      // Find question
      Question question = questions.stream().filter(q -> q.getId().toString().equals(questionId))
          .findFirst().orElse(null);

      if (question == null) {
        log.warn("Question not found for ID: {}", questionId);
        continue;
      }

      // Get value from row data
      Object cellValue = rowData.get(columnName);
      String stringValue = cellValue != null ? cellValue.toString().trim() : "";

      if (!stringValue.isEmpty() || Boolean.TRUE.equals(question.getRequired())) {
        // Convert position-based values to actual option values for choice questions
        String convertedValue = convertValueBasedOnQuestionType(stringValue, question);
        formData.put(questionId, convertedValue);
      }
    }

    return formData;
  }

  /**
   * Convert value based on question type For choice questions, convert position numbers to actual
   * option values
   */
  private String convertValueBasedOnQuestionType(String value, Question question) {
    if (value == null || value.trim().isEmpty()) {
      return value;
    }

    String questionType = question.getType().toLowerCase();

    switch (questionType) {
      case "radio":
      case "select":
      case "combobox":
        // Single choice - convert position to value
        return dataFillValidator.convertPositionToValue(value, question.getOptions());

      case "checkbox":
      case "multiselect":
        // Multiple choice - convert positions to values
        return dataFillValidator.convertMultiplePositionsToValues(value, question.getOptions());

      default:
        // For other question types, return value as-is
        return value;
    }
  }

  /**
   * Submit form data to Google Form This is a simplified implementation - you may need to adapt
   * based on your actual form submission logic
   */
  private boolean submitFormData(String formUrl, Map<String, String> formData) {
    try {
      // TODO: Implement actual form submission logic
      // This could use Google Forms API or HTTP POST to form submission endpoint

      log.debug("Submitting form data: {} to URL: {}", formData, formUrl);

      // Simulate form submission for now
      Thread.sleep(500 + (int) (Math.random() * 1000)); // Random delay 0.5-1.5s

      // Return success (in real implementation, check actual response)
      return true;

    } catch (Exception e) {
      log.error("Failed to submit form data", e);
      return false;
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
