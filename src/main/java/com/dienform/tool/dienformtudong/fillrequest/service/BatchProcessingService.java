package com.dienform.tool.dienformtudong.fillrequest.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestRepository;
import com.dienform.tool.dienformtudong.fillschedule.entity.FillSchedule;
import com.dienform.tool.dienformtudong.fillschedule.repository.FillScheduleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class BatchProcessingService {

  public static class BatchProgress {
    public static class Builder {
      private int currentBatch;
      private int totalBatches;
      private int batchSize;
      private LocalDateTime estimatedCompletionDate;
      private boolean adjustedForTimeConstraint;

      public Builder currentBatch(int currentBatch) {
        this.currentBatch = currentBatch;
        return this;
      }

      public Builder totalBatches(int totalBatches) {
        this.totalBatches = totalBatches;
        return this;
      }

      public Builder batchSize(int batchSize) {
        this.batchSize = batchSize;
        return this;
      }

      public Builder estimatedCompletionDate(LocalDateTime estimatedCompletionDate) {
        this.estimatedCompletionDate = estimatedCompletionDate;
        return this;
      }

      public Builder adjustedForTimeConstraint(boolean adjustedForTimeConstraint) {
        this.adjustedForTimeConstraint = adjustedForTimeConstraint;
        return this;
      }

      public BatchProgress build() {
        return new BatchProgress(currentBatch, totalBatches, batchSize, estimatedCompletionDate,
            adjustedForTimeConstraint);
      }
    }

    public static Builder builder() {
      return new Builder();
    }

    private final int currentBatch;
    private final int totalBatches;
    private final int batchSize;

    private final LocalDateTime estimatedCompletionDate;

    private final boolean adjustedForTimeConstraint;

    public BatchProgress(int currentBatch, int totalBatches, int batchSize,
        LocalDateTime estimatedCompletionDate, boolean adjustedForTimeConstraint) {
      this.currentBatch = currentBatch;
      this.totalBatches = totalBatches;
      this.batchSize = batchSize;
      this.estimatedCompletionDate = estimatedCompletionDate;
      this.adjustedForTimeConstraint = adjustedForTimeConstraint;
    }

    public int getCurrentBatch() {
      return currentBatch;
    }

    public int getTotalBatches() {
      return totalBatches;
    }

    public int getBatchSize() {
      return batchSize;
    }

    public LocalDateTime getEstimatedCompletionDate() {
      return estimatedCompletionDate;
    }

    public boolean isAdjustedForTimeConstraint() {
      return adjustedForTimeConstraint;
    }
  }

  @Autowired
  private FillScheduleRepository fillScheduleRepository;

  @Autowired
  private ScheduleDistributionService scheduleDistributionService;

  @Autowired
  private FillRequestRepository fillRequestRepository;

  @Value("${google.form.batch-size:100}")
  private int defaultBatchSize;

  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  /**
   * Create batch schedule and persist it
   */
  @Transactional
  public FillSchedule createBatchSchedule(FillRequest fillRequest, int batchSize) {
    log.info("Creating batch schedule for fillRequest: {} with batch size: {}", fillRequest.getId(),
        batchSize);

    // Check if schedule already exists
    Optional<FillSchedule> existingSchedule =
        fillScheduleRepository.findByFillRequestId(fillRequest.getId());

    if (existingSchedule.isPresent()) {
      log.info("Schedule already exists for fillRequest: {}, returning existing schedule",
          fillRequest.getId());
      return existingSchedule.get();
    }

    // Calculate total batches
    int totalBatches = (int) Math.ceil((double) fillRequest.getSurveyCount() / batchSize);

    // Create schedule with batching
    ScheduleDistributionService.BatchScheduleInfo batchInfo =
        scheduleDistributionService.distributeScheduleWithBatching(fillRequest.getSurveyCount(),
            fillRequest.getStartDate(), fillRequest.getEndDate(), fillRequest.isHumanLike(),
            fillRequest.getCompletedSurvey(), batchSize);

    // Serialize schedule data
    String scheduleData;
    try {
      scheduleData = objectMapper.writeValueAsString(batchInfo.getTasks());
    } catch (JsonProcessingException e) {
      log.error("Failed to serialize schedule data", e);
      throw new RuntimeException("Failed to create batch schedule", e);
    }

    // Create and save FillSchedule
    FillSchedule fillSchedule = FillSchedule.builder().fillRequest(fillRequest).batchSize(batchSize)
        .currentBatch(0).totalBatches(totalBatches).scheduleData(scheduleData)
        .estimatedCompletionDate(batchInfo.getEstimatedCompletionDate()).isScheduled(true)
        .isDynamicByTime(batchInfo.isAdjustedForTimeConstraint())
        .startDate(fillRequest.getStartDate().toLocalDate()).build();

    FillSchedule savedSchedule = fillScheduleRepository.save(fillSchedule);

    // Update FillRequest with estimated completion date
    fillRequest.setEstimatedCompletionDate(batchInfo.getEstimatedCompletionDate());

    log.info("Created batch schedule: {} batches, estimated completion: {}", totalBatches,
        batchInfo.getEstimatedCompletionDate());

    if (batchInfo.isAdjustedForTimeConstraint()) {
      log.warn(
          "Schedule was adjusted to fit within time constraint. Original delays were reduced.");
    }

    return savedSchedule;
  }

  /**
   * Get next batch of tasks for processing
   */
  @Transactional(readOnly = true)
  public Optional<List<ScheduleDistributionService.ScheduledTask>> getNextBatch(
      UUID fillRequestId) {
    Optional<FillSchedule> scheduleOpt =
        fillScheduleRepository.findIncompleteSchedule(fillRequestId);

    if (scheduleOpt.isEmpty()) {
      log.debug("No incomplete schedule found for fillRequest: {}", fillRequestId);
      return Optional.empty();
    }

    FillSchedule schedule = scheduleOpt.get();

    try {
      // Deserialize all tasks
      List<ScheduleDistributionService.ScheduledTask> allTasks =
          objectMapper.readValue(schedule.getScheduleData(),
              objectMapper.getTypeFactory().constructCollectionType(List.class,
                  ScheduleDistributionService.ScheduledTask.class));

      // Ensure deterministic order: sort by rowIndex asc
      allTasks.sort(java.util.Comparator.comparingInt(
          com.dienform.tool.dienformtudong.fillrequest.service.ScheduleDistributionService.ScheduledTask::getRowIndex));

      // Calculate batch boundaries
      int startIndex = schedule.getCurrentBatch() * schedule.getBatchSize();

      // Align with current completedSurvey so we don't pick rows below progress
      int completed = fillRequestRepository.findById(fillRequestId)
          .map(FillRequest::getCompletedSurvey).orElse(0);
      int baseIndex = 0;
      for (int i = 0; i < allTasks.size(); i++) {
        if (allTasks.get(i).getRowIndex() >= completed) {
          baseIndex = i;
          break;
        }
        if (i == allTasks.size() - 1) {
          baseIndex = allTasks.size();
        }
      }
      if (baseIndex > startIndex) {
        startIndex = baseIndex;
      }

      int endIndex = Math.min(startIndex + schedule.getBatchSize(), allTasks.size());

      if (startIndex >= allTasks.size()) {
        log.warn("All batches completed for fillRequest: {}", fillRequestId);
        return Optional.empty();
      }

      List<ScheduleDistributionService.ScheduledTask> batchTasks =
          allTasks.subList(startIndex, endIndex);

      log.info("Retrieved batch {}/{} for fillRequest: {} (tasks {}-{})",
          schedule.getCurrentBatch() + 1, schedule.getTotalBatches(), fillRequestId, startIndex,
          endIndex - 1);

      return Optional.of(batchTasks);

    } catch (JsonProcessingException e) {
      log.error("Failed to deserialize schedule data for fillRequest: {}", fillRequestId, e);
      return Optional.empty();
    }
  }

  /**
   * Mark current batch as completed and move to next batch
   */
  @Transactional
  public boolean completeCurrentBatch(UUID fillRequestId) {
    Optional<FillSchedule> scheduleOpt =
        fillScheduleRepository.findIncompleteSchedule(fillRequestId);

    if (scheduleOpt.isEmpty()) {
      log.debug("No incomplete schedule found for fillRequest: {}", fillRequestId);
      return false;
    }

    FillSchedule schedule = scheduleOpt.get();

    // Increment current batch
    int updated = fillScheduleRepository.incrementCurrentBatch(schedule.getId());

    if (updated > 0) {
      log.info("Completed batch {}/{} for fillRequest: {}", schedule.getCurrentBatch() + 1,
          schedule.getTotalBatches(), fillRequestId);
      return true;
    } else {
      log.warn("Failed to increment batch for fillRequest: {}", fillRequestId);
      return false;
    }
  }

  /**
   * Get batch progress information
   */
  @Transactional(readOnly = true)
  public BatchProgress getBatchProgress(UUID fillRequestId) {
    Optional<FillSchedule> scheduleOpt = fillScheduleRepository.findByFillRequestId(fillRequestId);

    if (scheduleOpt.isEmpty()) {
      return null;
    }

    FillSchedule schedule = scheduleOpt.get();

    return BatchProgress.builder().currentBatch(schedule.getCurrentBatch())
        .totalBatches(schedule.getTotalBatches()).batchSize(schedule.getBatchSize())
        .estimatedCompletionDate(schedule.getEstimatedCompletionDate())
        .adjustedForTimeConstraint(schedule.isDynamicByTime()).build();
  }

  /**
   * Check if all batches are completed
   */
  @Transactional(readOnly = true)
  public boolean isAllBatchesCompleted(UUID fillRequestId) {
    Optional<FillSchedule> scheduleOpt =
        fillScheduleRepository.findIncompleteSchedule(fillRequestId);
    return scheduleOpt.isEmpty();
  }
}
