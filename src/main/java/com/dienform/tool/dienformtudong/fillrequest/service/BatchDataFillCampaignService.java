package com.dienform.tool.dienformtudong.fillrequest.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.tool.dienformtudong.datamapping.dto.request.DataFillRequestDTO;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.fillschedule.entity.FillSchedule;
import com.dienform.tool.dienformtudong.question.entity.Question;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class BatchDataFillCampaignService {

  @Autowired
  private DataFillCampaignService dataFillCampaignService;

  @Autowired
  private BatchProcessingService batchProcessingService;

  @Autowired
  private ThreadPoolManager threadPoolManager;

  @Value("${google.form.enable-batch-processing:true}")
  private boolean enableBatchProcessing;

  @Value("${google.form.batch-size:100}")
  private int defaultBatchSize;

  /**
   * Execute campaign with batch processing
   */
  public CompletableFuture<Void> executeBatchCampaign(FillRequest fillRequest,
      DataFillRequestDTO originalRequest, List<Question> questions) {

    if (!enableBatchProcessing) {
      log.info("Batch processing disabled, using legacy execution for fillRequest: {}",
          fillRequest.getId());
      return dataFillCampaignService.executeCampaign(fillRequest, originalRequest, questions);
    }

    log.info("Starting batch campaign execution for fillRequest: {} with {} forms",
        fillRequest.getId(), fillRequest.getSurveyCount());

    // Create batch schedule and persist it
    FillSchedule fillSchedule =
        batchProcessingService.createBatchSchedule(fillRequest, defaultBatchSize);

    // Get the first batch
    Optional<List<ScheduleDistributionService.ScheduledTask>> firstBatch =
        batchProcessingService.getNextBatch(fillRequest.getId());

    if (firstBatch.isEmpty()) {
      log.warn("No batches available for fillRequest: {}", fillRequest.getId());
      return CompletableFuture.completedFuture(null);
    }

    // Execute first batch
    return executeBatch(fillRequest, originalRequest, questions, firstBatch.get(), fillSchedule);
  }

  /**
   * Get batch progress for a fill request
   */
  public BatchProcessingService.BatchProgress getBatchProgress(UUID fillRequestId) {
    return batchProcessingService.getBatchProgress(fillRequestId);
  }

  /**
   * Check if batch processing is enabled
   */
  public boolean isBatchProcessingEnabled() {
    return enableBatchProcessing;
  }

  /**
   * Get current batch size
   */
  public int getBatchSize() {
    return defaultBatchSize;
  }

  /**
   * Complete current batch in a separate transaction
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean completeCurrentBatchInTransaction(UUID fillRequestId) {
    return batchProcessingService.completeCurrentBatch(fillRequestId);
  }

  /**
   * Get next batch in a separate transaction
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<List<ScheduleDistributionService.ScheduledTask>> getNextBatchInTransaction(
      UUID fillRequestId) {
    return batchProcessingService.getNextBatch(fillRequestId);
  }

  /**
   * Execute a single batch
   */
  private CompletableFuture<Void> executeBatch(FillRequest fillRequest,
      DataFillRequestDTO originalRequest, List<Question> questions,
      List<ScheduleDistributionService.ScheduledTask> batchTasks, FillSchedule fillSchedule) {

    log.info("Executing batch {}/{} for fillRequest: {} with {} tasks",
        fillSchedule.getCurrentBatch() + 1, fillSchedule.getTotalBatches(), fillRequest.getId(),
        batchTasks.size());

    // Get appropriate executor
    ExecutorService executor = threadPoolManager.getExecutor(fillRequest.isHumanLike());

    // Execute batch using existing DataFillCampaignService logic
    CompletableFuture<Void> batchFuture = dataFillCampaignService.executeBatchTasks(fillRequest,
        originalRequest, questions, batchTasks, executor);

    // When batch completes, check if there are more batches
    return batchFuture.thenCompose(v -> {
      try {
        // Mark current batch as completed in a separate transaction
        boolean batchCompleted = completeCurrentBatchInTransaction(fillRequest.getId());

        if (!batchCompleted) {
          log.warn("Failed to mark batch as completed for fillRequest: {}", fillRequest.getId());
        }

        // Check if there are more batches in a separate transaction
        Optional<List<ScheduleDistributionService.ScheduledTask>> nextBatch =
            getNextBatchInTransaction(fillRequest.getId());

        if (nextBatch.isPresent()) {
          log.info("Scheduling next batch for fillRequest: {}", fillRequest.getId());
          // Execute next batch
          return executeBatch(fillRequest, originalRequest, questions, nextBatch.get(),
              fillSchedule);
        } else {
          log.info("All batches completed for fillRequest: {}", fillRequest.getId());
          return CompletableFuture.completedFuture(null);
        }
      } catch (Exception e) {
        log.error("Error in batch completion processing: {}", e.getMessage(), e);
        return CompletableFuture.completedFuture(null);
      }
    });
  }
}
