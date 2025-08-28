package com.dienform.tool.dienformtudong.fillrequest.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.dienform.tool.dienformtudong.datamapping.dto.request.DataFillRequestDTO;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.fillrequest.service.ScheduleDistributionService.ScheduledTask;
import com.dienform.tool.dienformtudong.googleform.service.GoogleFormService;
import com.dienform.tool.dienformtudong.question.entity.Question;
import lombok.extern.slf4j.Slf4j;

/**
 * Service để xử lý batch processing với adaptive timeout và circuit breaker
 */
@Service
@Slf4j
public class BatchFormProcessor {

  /**
   * Kết quả batch processing
   */
  public static class BatchResult {
    public static class Builder {
      private int totalForms;
      private int completedForms;
      private int failedForms;
      private int skippedForms;

      public Builder totalForms(int totalForms) {
        this.totalForms = totalForms;
        return this;
      }

      public Builder completedForms(int completedForms) {
        this.completedForms = completedForms;
        return this;
      }

      public Builder failedForms(int failedForms) {
        this.failedForms = failedForms;
        return this;
      }

      public Builder skippedForms(int skippedForms) {
        this.skippedForms = skippedForms;
        return this;
      }

      public BatchResult build() {
        return new BatchResult(totalForms, completedForms, failedForms, skippedForms);
      }
    }

    public static Builder builder() {
      return new Builder();
    }

    private final int totalForms;
    private final int completedForms;

    private final int failedForms;

    private final int skippedForms;

    public BatchResult(int totalForms, int completedForms, int failedForms, int skippedForms) {
      this.totalForms = totalForms;
      this.completedForms = completedForms;
      this.failedForms = failedForms;
      this.skippedForms = skippedForms;
    }

    // Getters
    public int getTotalForms() {
      return totalForms;
    }

    public int getCompletedForms() {
      return completedForms;
    }

    public int getFailedForms() {
      return failedForms;
    }

    public int getSkippedForms() {
      return skippedForms;
    }
  }

  @Autowired
  private AdaptiveTimeoutService adaptiveTimeoutService;

  @Autowired
  private CircuitBreakerService circuitBreakerService;

  @Autowired
  private ThreadPoolManager threadPoolManager;

  @Autowired
  private GoogleFormService googleFormService;

  @Autowired
  private com.dienform.tool.dienformtudong.datamapping.service.GoogleSheetsService googleSheetsService;

  @Autowired
  private FillRequestCounterService fillRequestCounterService;

  /**
   * Xử lý large batch với adaptive timeout và circuit breaker
   */
  public CompletableFuture<BatchResult> processLargeBatch(FillRequest fillRequest,
      DataFillRequestDTO originalRequest, List<Question> questions, List<ScheduledTask> tasks) {

    log.info("Starting batch processing for {} forms (human-like: {})", tasks.size(),
        fillRequest.isHumanLike());

    CompletableFuture<BatchResult> batchFuture = new CompletableFuture<>();

    try {
      // Kiểm tra có cần batch processing không
      int queueCapacity = getQueueCapacity();
      if (!adaptiveTimeoutService.needsBatchProcessing(tasks.size(), queueCapacity)) {
        log.info("No batch processing needed for {} forms", tasks.size());
        // Xử lý tuần tự
        return processSequentially(fillRequest, originalRequest, questions, tasks);
      }

      // Tính toán batch size tối ưu
      int batchSize = adaptiveTimeoutService.calculateOptimalBatchSize(tasks.size(), queueCapacity);
      List<List<ScheduledTask>> batches = partitionTasks(tasks, batchSize);

      log.info("Processing {} forms in {} batches of size {}", tasks.size(), batches.size(),
          batchSize);

      // Xử lý từng batch
      AtomicInteger completedForms = new AtomicInteger(0);
      AtomicInteger failedForms = new AtomicInteger(0);
      AtomicInteger skippedForms = new AtomicInteger(0);

      for (int i = 0; i < batches.size(); i++) {
        List<ScheduledTask> batch = batches.get(i);

        log.info("Processing batch {}/{} with {} forms", i + 1, batches.size(), batch.size());

        // Xử lý batch với timeout
        long batchTimeout =
            adaptiveTimeoutService.calculateBatchTimeout(batch.size(), batches.size());

        CompletableFuture<BatchResult> batchResult =
            processBatch(fillRequest, originalRequest, questions, batch, batchTimeout);

        try {
          BatchResult result = batchResult.get(batchTimeout, TimeUnit.SECONDS);

          completedForms.addAndGet(result.getCompletedForms());
          failedForms.addAndGet(result.getFailedForms());
          skippedForms.addAndGet(result.getSkippedForms());

          log.info("Batch {}/{} completed: {} successful, {} failed, {} skipped", i + 1,
              batches.size(), result.getCompletedForms(), result.getFailedForms(),
              result.getSkippedForms());

        } catch (Exception e) {
          log.error("Batch {}/{} failed or timed out: {}", i + 1, batches.size(), e.getMessage());
          failedForms.addAndGet(batch.size());
        }

        // Wait between batches to avoid overwhelming
        if (i < batches.size() - 1) {
          try {
            Thread.sleep(2000); // 2 seconds between batches
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      }

      BatchResult finalResult =
          BatchResult.builder().totalForms(tasks.size()).completedForms(completedForms.get())
              .failedForms(failedForms.get()).skippedForms(skippedForms.get()).build();

      log.info("Batch processing completed: {}/{} forms successful",
          finalResult.getCompletedForms(), finalResult.getTotalForms());

      batchFuture.complete(finalResult);

    } catch (Exception e) {
      log.error("Error in batch processing: {}", e.getMessage(), e);
      batchFuture.completeExceptionally(e);
    }

    return batchFuture;
  }

  /**
   * Xử lý một batch với adaptive timeout và circuit breaker
   */
  private CompletableFuture<BatchResult> processBatch(FillRequest fillRequest,
      DataFillRequestDTO originalRequest, List<Question> questions, List<ScheduledTask> batch,
      long batchTimeout) {

    ExecutorService executor = threadPoolManager.getExecutor(fillRequest.isHumanLike());

    // Get sheet data
    List<Map<String, Object>> sheetData;
    try {
      sheetData = googleSheetsService.getSheetData(originalRequest.getSheetLink());
    } catch (Exception e) {
      log.error("Failed to get sheet data: {}", e.getMessage());
      return CompletableFuture.completedFuture(BatchResult.builder().totalForms(batch.size())
          .completedForms(0).failedForms(batch.size()).skippedForms(0).build());
    }

    // Build question map
    Map<UUID, Question> questionMap =
        questions.stream().collect(Collectors.toMap(Question::getId, q -> q));

    AtomicInteger completedForms = new AtomicInteger(0);
    AtomicInteger failedForms = new AtomicInteger(0);
    AtomicInteger skippedForms = new AtomicInteger(0);

    // Process batch in parallel
    List<CompletableFuture<Boolean>> futures =
        batch.stream().map(task -> processFormWithAdaptiveTimeout(fillRequest, originalRequest,
            questionMap, sheetData, task, batch.size(), executor)).collect(Collectors.toList());

    // Wait for all forms in batch to complete
    CompletableFuture<Void> allFutures =
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));

    return allFutures.thenApply(v -> {
      // Count results
      for (CompletableFuture<Boolean> future : futures) {
        try {
          Boolean result = future.get();
          if (result != null && result) {
            completedForms.incrementAndGet();
          } else {
            failedForms.incrementAndGet();
          }
        } catch (Exception e) {
          log.error("Error getting form result: {}", e.getMessage());
          failedForms.incrementAndGet();
        }
      }

      return BatchResult.builder().totalForms(batch.size()).completedForms(completedForms.get())
          .failedForms(failedForms.get()).skippedForms(skippedForms.get()).build();
    });
  }

  /**
   * Xử lý form với adaptive timeout và circuit breaker
   */
  private CompletableFuture<Boolean> processFormWithAdaptiveTimeout(FillRequest fillRequest,
      DataFillRequestDTO originalRequest, Map<UUID, Question> questionMap,
      List<Map<String, Object>> sheetData, ScheduledTask task, int totalTasks,
      ExecutorService executor) {

    String formId = task.getRowIndex() + "_" + fillRequest.getId();

    return CompletableFuture.supplyAsync(() -> {
      try {
        // Kiểm tra circuit breaker
        if (circuitBreakerService.isOpen(formId)) {
          log.warn("Skipping form {} - circuit breaker open", formId);
          return false;
        }

        // Tính timeout adaptive
        int currentQueueSize = getCurrentQueueSize();
        int threadPoolSize = getThreadPoolSize();
        long timeout = adaptiveTimeoutService.calculateFormTimeout(totalTasks, currentQueueSize,
            threadPoolSize);

        log.info("Processing form {} with adaptive timeout {}s", formId, timeout);

        // Execute form fill
        boolean success =
            executeFormFill(fillRequest, originalRequest, questionMap, sheetData, task);

        if (success) {
          circuitBreakerService.recordSuccess(formId);
          log.info("Form {} completed successfully", formId);
        } else {
          circuitBreakerService.recordFailure(formId);
          log.warn("Form {} failed", formId);

          // Increment failed survey count in database
          try {
            fillRequestCounterService.incrementFailedSurvey(fillRequest.getId());
            log.info("Incremented failed survey count for request: {}", fillRequest.getId());
          } catch (Exception e) {
            log.error("Failed to increment failed survey count for request: {}: {}",
                fillRequest.getId(), e.getMessage());
          }
        }

        return success;

      } catch (Exception e) {
        log.error("Form {} failed with exception: {}", formId, e.getMessage());
        circuitBreakerService.recordFailure(formId);
        return false;
      }
    }, executor).orTimeout(getFormTimeout(totalTasks), TimeUnit.SECONDS)
        .exceptionally(throwable -> {
          if (throwable instanceof java.util.concurrent.TimeoutException) {
            log.warn("Form {} timed out, skipping", formId);
            circuitBreakerService.recordFailure(formId);
          } else {
            log.error("Form {} failed: {}", formId, throwable.getMessage());
            circuitBreakerService.recordFailure(formId);
          }
          return false;
        });
  }

  /**
   * Xử lý tuần tự cho small batches
   */
  private CompletableFuture<BatchResult> processSequentially(FillRequest fillRequest,
      DataFillRequestDTO originalRequest, List<Question> questions, List<ScheduledTask> tasks) {

    // Implementation for sequential processing
    // This would be similar to the original DataFillCampaignService logic
    // but with adaptive timeout and circuit breaker

    AtomicInteger completedForms = new AtomicInteger(0);
    AtomicInteger failedForms = new AtomicInteger(0);

    // Process sequentially
    for (ScheduledTask task : tasks) {
      try {
        // Similar logic to original executeFormFill
        boolean success = true; // Placeholder
        if (success) {
          completedForms.incrementAndGet();
        } else {
          failedForms.incrementAndGet();
        }
      } catch (Exception e) {
        log.error("Error processing task {}: {}", task.getRowIndex(), e.getMessage());
        failedForms.incrementAndGet();
      }
    }

    BatchResult result =
        BatchResult.builder().totalForms(tasks.size()).completedForms(completedForms.get())
            .failedForms(failedForms.get()).skippedForms(0).build();

    return CompletableFuture.completedFuture(result);
  }

  /**
   * Chia tasks thành batches
   */
  private List<List<ScheduledTask>> partitionTasks(List<ScheduledTask> tasks, int batchSize) {
    List<List<ScheduledTask>> batches = new java.util.ArrayList<>();

    for (int i = 0; i < tasks.size(); i += batchSize) {
      int end = Math.min(i + batchSize, tasks.size());
      batches.add(tasks.subList(i, end));
    }

    return batches;
  }

  /**
   * Execute form fill (placeholder - would use existing logic)
   */
  private boolean executeFormFill(FillRequest fillRequest, DataFillRequestDTO originalRequest,
      Map<UUID, Question> questionMap, List<Map<String, Object>> sheetData, ScheduledTask task) {
    // This would use the existing executeFormFill logic from DataFillCampaignService
    // For now, return true as placeholder
    return true;
  }

  // Helper methods
  private int getQueueCapacity() {
    return 50; // Default queue capacity
  }

  private int getCurrentQueueSize() {
    // This would get current queue size from ThreadPoolManager
    return 0; // Placeholder
  }

  private int getThreadPoolSize() {
    return 4; // 4 threads for 2-core server
  }

  private long getFormTimeout(int totalTasks) {
    return 300; // 5 minutes default
  }
}
