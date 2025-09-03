package com.dienform.tool.dienformtudong.fillrequest.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.springframework.stereotype.Service;
import com.dienform.common.util.DateTimeUtil;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ScheduleDistributionService {

  public static class ScheduledTask {
    private final LocalDateTime executionTime;
    private final int delaySeconds;
    private final int rowIndex;

    // Jackson constructor
    public ScheduledTask() {
      this.executionTime = null;
      this.delaySeconds = 0;
      this.rowIndex = 0;
    }

    public ScheduledTask(LocalDateTime executionTime, int delaySeconds, int rowIndex) {
      this.executionTime = executionTime;
      this.delaySeconds = delaySeconds;
      this.rowIndex = rowIndex;
    }

    public LocalDateTime getExecutionTime() {
      return executionTime;
    }

    public int getDelaySeconds() {
      return delaySeconds;
    }

    public int getRowIndex() {
      return rowIndex;
    }
  }

  public static class BatchScheduleInfo {
    private final List<ScheduledTask> tasks;
    private final LocalDateTime estimatedCompletionDate;
    private final boolean adjustedForTimeConstraint;

    public BatchScheduleInfo(List<ScheduledTask> tasks, LocalDateTime estimatedCompletionDate,
        boolean adjustedForTimeConstraint) {
      this.tasks = tasks;
      this.estimatedCompletionDate = estimatedCompletionDate;
      this.adjustedForTimeConstraint = adjustedForTimeConstraint;
    }

    public List<ScheduledTask> getTasks() {
      return tasks;
    }

    public LocalDateTime getEstimatedCompletionDate() {
      return estimatedCompletionDate;
    }

    public boolean isAdjustedForTimeConstraint() {
      return adjustedForTimeConstraint;
    }
  }
  /**
   * Inner class for time slots
   */
  private static class TimeSlot {
    private final LocalDateTime start;
    private final LocalDateTime end;
    private final double weight;

    public TimeSlot(LocalDateTime start, LocalDateTime end, double weight) {
      this.start = start;
      this.end = end;
      this.weight = weight;
    }

    public LocalDateTime getRandomTimeInSlot() {
      long totalSeconds = java.time.Duration.between(start, end).getSeconds();
      if (totalSeconds <= 0)
        return start;

      long randomSeconds = random.nextLong(totalSeconds);
      return start.plusSeconds(randomSeconds);
    }

    public double getWeight() {
      return weight;
    }
  }

  private static final ZoneId VIETNAM_TIMEZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  private static final Random random = new Random();

  /**
   * Distribute form filling tasks across time period with human-like behavior
   */
  public List<ScheduledTask> distributeSchedule(int submissionCount, LocalDateTime startDate,
      LocalDateTime endDate, boolean isHumanLike) {
    return distributeSchedule(submissionCount, startDate, endDate, isHumanLike, 0);
  }

  /**
   * Distribute form filling tasks across time period with human-like behavior
   * 
   * @param completedSurvey Number of surveys already completed (affects first form delay)
   */
  public List<ScheduledTask> distributeSchedule(int submissionCount, LocalDateTime startDate,
      LocalDateTime endDate, boolean isHumanLike, int completedSurvey) {
    List<ScheduledTask> schedule = new ArrayList<>();

    if (endDate == null) {
      // If no end date, schedule all tasks starting from start date
      return distributeWithoutEndDate(submissionCount, startDate, isHumanLike, completedSurvey);
    }

    // CRITICAL FIX: If startDate equals endDate OR non-human-like, execute immediately without
    // scheduling delays
    if (startDate.equals(endDate) || !isHumanLike) {
      log.info("Execute immediately - startDate equals endDate: {} OR non-human-like: {}",
          startDate.equals(endDate), !isHumanLike);
      return distributeWithoutEndDate(submissionCount, startDate, isHumanLike, completedSurvey);
    }

    // Convert to Vietnam timezone for realistic scheduling
    ZonedDateTime startZoned = startDate.atZone(VIETNAM_TIMEZONE);
    ZonedDateTime endZoned = endDate.atZone(VIETNAM_TIMEZONE);

    log.info(
        "Distributing {} submissions from {} to {} (Vietnam time), humanLike: {}, completedSurvey: {}",
        submissionCount, startZoned, endZoned, isHumanLike, completedSurvey);

    if (isHumanLike) {
      schedule = createHumanLikeSchedule(submissionCount, startZoned, endZoned, completedSurvey);
    } else {
      schedule = createFastSchedule(submissionCount, startZoned, endZoned, completedSurvey);
    }

    return schedule;
  }

  /**
   * Distribute form filling tasks with batch processing and smart delay adjustment
   * 
   * @param submissionCount Total number of forms to fill
   * @param startDate Start date/time
   * @param endDate End date/time (can be null)
   * @param isHumanLike Whether to use human-like delays
   * @param completedSurvey Number of completed surveys
   * @param batchSize Size of each batch
   * @return BatchScheduleInfo containing tasks and estimated completion
   */
  public BatchScheduleInfo distributeScheduleWithBatching(int submissionCount,
      LocalDateTime startDate, LocalDateTime endDate, boolean isHumanLike, int completedSurvey,
      int batchSize) {

    log.info("Creating batch schedule for {} forms with batch size {}", submissionCount, batchSize);

    // Calculate estimated completion with original delays
    LocalDateTime estimatedCompletion = calculateEstimatedCompletion(submissionCount, startDate,
        endDate, isHumanLike, completedSurvey);

    boolean adjustedForTimeConstraint = false;

    // Check if we need to adjust delays for time constraint
    if (endDate != null && estimatedCompletion.isAfter(endDate)) {
      log.warn(
          "Estimated completion {} exceeds end date {}. Adjusting delays to fit within time constraint.",
          estimatedCompletion, endDate);

      // Recalculate with adjusted delays
      estimatedCompletion = calculateEstimatedCompletionWithAdjustedDelays(submissionCount,
          startDate, endDate, isHumanLike, completedSurvey);
      adjustedForTimeConstraint = true;

      log.info("Adjusted estimated completion: {}", estimatedCompletion);
    }

    // Create schedule (will be split into batches later)
    List<ScheduledTask> allTasks =
        distributeSchedule(submissionCount, startDate, endDate, isHumanLike, completedSurvey);

    return new BatchScheduleInfo(allTasks, estimatedCompletion, adjustedForTimeConstraint);
  }

  /**
   * Create human-like schedule with realistic patterns
   */
  private List<ScheduledTask> createHumanLikeSchedule(int submissionCount, ZonedDateTime start,
      ZonedDateTime end, int completedSurvey) {
    List<ScheduledTask> schedule = new ArrayList<>();

    long totalMinutes = java.time.Duration.between(start, end).toMinutes();

    // Create time slots with different probabilities
    List<TimeSlot> timeSlots = createHumanTimeSlots(start, end);

    for (int i = 0; i < submissionCount; i++) {
      TimeSlot selectedSlot = selectWeightedTimeSlot(timeSlots);
      LocalDateTime executionTime = selectedSlot.getRandomTimeInSlot();

      // IMPROVED LOGIC: First form executes immediately, subsequent forms have distributed delays
      int delaySeconds;
      if (completedSurvey == 0 && i == 0) {
        delaySeconds = 0; // First form: execute immediately
        executionTime = DateTimeUtil.now(); // Override to current time for immediate execution
        log.debug("Human-like mode: First form with immediate execution (delay: {} seconds)",
            delaySeconds);
      } else {
        // Subsequent forms: distributed delays between 2-15 minutes (120-900 seconds)
        delaySeconds = 120 + random.nextInt(780); // 120-899 seconds (2-15 minutes)
        log.debug("Human-like mode: Form {} with delay of {} seconds ({} minutes)", i + 1,
            delaySeconds, delaySeconds / 60);
      }

      // FIX: Sử dụng completedSurvey + i để tiếp tục từ vị trí đã dừng
      // Khi completedSurvey = 1: survey 2 = row 2, survey 3 = row 3, survey 4 = row 4...
      int rowIndex = completedSurvey + i;
      schedule.add(new ScheduledTask(executionTime, delaySeconds, rowIndex));
    }

    // FIX: Không sort theo execution time để đảm bảo thứ tự row đúng
    // Thứ tự row sẽ luôn là: 0, 1, 2, 3... (tương ứng với Row 1, Row 2, Row 3...)
    log.info("Human-like schedule created with {} tasks - maintaining row order: 0,1,2,3...",
        schedule.size());

    return schedule;
  }

  /**
   * Create fast schedule for non-human-like filling
   */
  private List<ScheduledTask> createFastSchedule(int submissionCount, ZonedDateTime start,
      ZonedDateTime end, int completedSurvey) {
    List<ScheduledTask> schedule = new ArrayList<>();

    long totalSeconds = java.time.Duration.between(start, end).getSeconds();
    long intervalSeconds = totalSeconds / submissionCount;

    // Minimum interval of 1 second, maximum interval calculated
    intervalSeconds = Math.max(1, intervalSeconds);

    LocalDateTime currentTime = start.toLocalDateTime();

    for (int i = 0; i < submissionCount; i++) {
      // EXACT LOGIC FROM API /form/{formId}/fill-request (GoogleFormServiceImpl)
      int delaySeconds;
      if (i > 0) {
        delaySeconds = 1; // Fast mode: 1 second between forms
      } else {
        delaySeconds = 0; // First form: no delay
      }

      // FIX: Sử dụng completedSurvey + i để tiếp tục từ vị trí đã dừng
      // Khi completedSurvey = 1: survey 2 = row 2, survey 3 = row 3, survey 4 = row 4...
      int rowIndex = completedSurvey + i;
      schedule.add(new ScheduledTask(currentTime, delaySeconds, rowIndex));

      // Add interval for next task
      long interval = intervalSeconds;
      currentTime = currentTime.plusSeconds(Math.max(1, interval));

      // Don't exceed end date
      if (currentTime.isAfter(end.toLocalDateTime())) {
        currentTime = end.toLocalDateTime().minusSeconds(submissionCount - i - 1);
      }
    }

    return schedule;
  }

  /**
   * Distribute without end date - start immediately
   */
  private List<ScheduledTask> distributeWithoutEndDate(int submissionCount, LocalDateTime startDate,
      boolean isHumanLike, int completedSurvey) {
    List<ScheduledTask> schedule = new ArrayList<>();
    LocalDateTime currentTime = startDate;

    log.info(
        "Creating immediate execution schedule for {} tasks (humanLike: {}, completedSurvey: {})",
        submissionCount, isHumanLike, completedSurvey);

    for (int i = 0; i < submissionCount; i++) {
      int delaySeconds;

      // IMPROVED LOGIC: First form executes immediately, subsequent forms have distributed delays
      if (isHumanLike) {
        if (completedSurvey == 0 && i == 0) {
          delaySeconds = 0; // First form: execute immediately
          currentTime = DateTimeUtil.now(); // Set to current time for immediate execution
          log.debug("Human-like mode: First form with immediate execution (delay: {} seconds)",
              delaySeconds);
        } else {
          // Subsequent forms: distributed delays between 2-15 minutes (120-900 seconds)
          delaySeconds = 120 + random.nextInt(780); // 120-899 seconds (2-15 minutes)
          currentTime = DateTimeUtil.now().plusSeconds(delaySeconds);
          log.debug("Human-like mode: Form {} with delay of {} seconds ({} minutes)", i + 1,
              delaySeconds, delaySeconds / 60);
        }
      } else {
        // Fast mode: minimal delays
        if (i == 0) {
          delaySeconds = 0; // First form: no delay
          currentTime = DateTimeUtil.now(); // Set to current time for immediate execution
          log.debug("Fast mode: First form with immediate execution (delay: {} seconds)",
              delaySeconds);
        } else {
          delaySeconds = 1; // Subsequent forms: 1 second between forms
          currentTime = DateTimeUtil.now().plusSeconds(delaySeconds);
          log.debug("Fast mode: Form {} with 1 second delay", i + 1);
        }
      }

      // FIX: Sử dụng completedSurvey + i để tiếp tục từ vị trí đã dừng
      // Khi completedSurvey = 1: survey 2 = row 2, survey 3 = row 3, survey 4 = row 4...
      int rowIndex = completedSurvey + i;
      schedule.add(new ScheduledTask(currentTime, delaySeconds, rowIndex));
    }

    log.info("Created {} immediate execution tasks starting at {}", schedule.size(), startDate);
    return schedule;
  }

  /**
   * Create time slots with realistic human activity patterns
   */
  private List<TimeSlot> createHumanTimeSlots(ZonedDateTime start, ZonedDateTime end) {
    List<TimeSlot> slots = new ArrayList<>();

    ZonedDateTime current = start;

    while (current.isBefore(end)) {
      ZonedDateTime dayEnd = current.toLocalDate().atTime(23, 59).atZone(VIETNAM_TIMEZONE);
      ZonedDateTime slotEnd = end.isBefore(dayEnd) ? end : dayEnd;

      // Create hourly slots for the day
      for (int hour = current.getHour(); hour <= slotEnd.getHour()
          && current.isBefore(slotEnd); hour++) {
        ZonedDateTime slotStart = current.toLocalDate().atTime(hour, 0).atZone(VIETNAM_TIMEZONE);
        ZonedDateTime slotEndTime =
            current.toLocalDate().atTime(hour, 59, 59).atZone(VIETNAM_TIMEZONE);

        if (slotStart.isBefore(current))
          slotStart = current;
        if (slotEndTime.isAfter(slotEnd))
          slotEndTime = slotEnd;

        if (slotStart.isBefore(slotEndTime)) {
          double weight = getHourWeight(hour);
          slots.add(
              new TimeSlot(slotStart.toLocalDateTime(), slotEndTime.toLocalDateTime(), weight));
        }
      }

      current = current.toLocalDate().plusDays(1).atTime(0, 0).atZone(VIETNAM_TIMEZONE);
    }

    return slots;
  }

  /**
   * Get weight for hour based on typical human web activity
   */
  private double getHourWeight(int hour) {
    // Higher weights for lunch (12-14), afternoon (15-17), evening (19-22)
    if (hour >= 12 && hour <= 14)
      return 1.5; // Lunch break
    if (hour >= 15 && hour <= 17)
      return 1.3; // Afternoon
    if (hour >= 19 && hour <= 22)
      return 1.8; // Evening
    if (hour >= 8 && hour <= 11)
      return 1.0; // Morning work
    if (hour >= 18 && hour <= 18)
      return 1.2; // After work
    if (hour >= 23 || hour <= 6)
      return 0.1; // Night/early morning
    return 0.8; // Default
  }

  /**
   * Select time slot based on weights
   */
  private TimeSlot selectWeightedTimeSlot(List<TimeSlot> slots) {
    double totalWeight = slots.stream().mapToDouble(TimeSlot::getWeight).sum();
    double randomValue = random.nextDouble() * totalWeight;

    double currentWeight = 0;
    for (TimeSlot slot : slots) {
      currentWeight += slot.getWeight();
      if (randomValue <= currentWeight) {
        return slot;
      }
    }

    // Fallback to last slot
    return slots.get(slots.size() - 1);
  }

  /**
   * Calculate estimated completion date based on delays
   */
  private LocalDateTime calculateEstimatedCompletion(int submissionCount, LocalDateTime startDate,
      LocalDateTime endDate, boolean isHumanLike, int completedSurvey) {

    LocalDateTime currentTime = startDate;

    for (int i = 0; i < submissionCount; i++) {
      int delaySeconds;

      if (isHumanLike) {
        if (completedSurvey == 0 && i == 0) {
          delaySeconds = 0;
        } else {
          delaySeconds = 120 + random.nextInt(780); // 2-15 minutes
        }
      } else {
        if (i == 0) {
          delaySeconds = 0;
        } else {
          delaySeconds = 1; // 1 second
        }
      }

      currentTime = currentTime.plusSeconds(delaySeconds);
    }

    return currentTime;
  }

  /**
   * Calculate estimated completion with adjusted delays to fit within time constraint
   */
  private LocalDateTime calculateEstimatedCompletionWithAdjustedDelays(int submissionCount,
      LocalDateTime startDate, LocalDateTime endDate, boolean isHumanLike, int completedSurvey) {

    if (endDate == null) {
      return calculateEstimatedCompletion(submissionCount, startDate, endDate, isHumanLike,
          completedSurvey);
    }

    Duration availableTime = Duration.between(startDate, endDate);
    long availableSeconds = availableTime.getSeconds();

    if (availableSeconds <= 0) {
      log.warn("No time available between start and end date. Using original schedule.");
      return calculateEstimatedCompletion(submissionCount, startDate, endDate, isHumanLike,
          completedSurvey);
    }

    // Calculate adjusted delay per form
    long adjustedDelaySeconds;
    if (isHumanLike) {
      // Reserve some time for actual form filling (assume 2 minutes per form)
      long formFillingTime = submissionCount * 120; // 2 minutes per form
      long availableForDelays = Math.max(0, availableSeconds - formFillingTime);
      adjustedDelaySeconds = availableForDelays / Math.max(1, submissionCount - 1);

      // Ensure minimum delay of 30 seconds for human-like
      adjustedDelaySeconds = Math.max(30, adjustedDelaySeconds);

      log.info("Adjusted human-like delay: {} seconds (original: 120-900 seconds)",
          adjustedDelaySeconds);
    } else {
      // For fast mode, keep 1 second delay
      adjustedDelaySeconds = 1;
    }

    LocalDateTime currentTime = startDate;
    for (int i = 0; i < submissionCount; i++) {
      if (i > 0) {
        currentTime = currentTime.plusSeconds(adjustedDelaySeconds);
      }
    }

    return currentTime;
  }
}
