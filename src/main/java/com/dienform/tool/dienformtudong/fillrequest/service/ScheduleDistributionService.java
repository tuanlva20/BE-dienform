package com.dienform.tool.dienformtudong.fillrequest.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.springframework.beans.factory.annotation.Value;
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

    public LocalDateTime getStart() {
      return start;
    }

    public LocalDateTime getEnd() {
      return end;
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
  @Value("${google.form.min-gap-human:120}")
  private int minGapHumanSeconds;

  @Value("${google.form.max-gap-human:900}")
  private int maxGapHumanSeconds;

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

    // endDate is required by FE; fall back to same-day end if somehow null
    if (endDate == null && startDate != null) {
      endDate = startDate.toLocalDate().atTime(23, 59);
    }

    if (startDate == null || endDate == null) {
      log.warn("Invalid date range: startDate={}, endDate={}", startDate, endDate);
      return schedule;
    }

    // CRITICAL FIX: Ensure startDate is not in the past
    LocalDateTime now = com.dienform.common.util.DateTimeUtil.now();
    if (startDate.isBefore(now)) {
      startDate = now.plusMinutes(1); // Start 1 minute from now
      log.info("Adjusted startDate to current time + 1 minute: {}", startDate);
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

    // CRITICAL FIX: Sort tasks by row_index first, then by execution time
    // This ensures row 3 always executes before row 6, regardless of execution time
    schedule.sort((a, b) -> {
      // First priority: sort by row_index (ascending)
      int rowIndexCompare = Integer.compare(a.getRowIndex(), b.getRowIndex());
      if (rowIndexCompare != 0) {
        return rowIndexCompare;
      }

      // Second priority: sort by execution time (ascending)
      if (a.getExecutionTime() == null && b.getExecutionTime() == null)
        return 0;
      if (a.getExecutionTime() == null)
        return 1;
      if (b.getExecutionTime() == null)
        return -1;
      return a.getExecutionTime().compareTo(b.getExecutionTime());
    });

    log.info("Created {} scheduled tasks, sorted by row_index then execution time",
        schedule.size());
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
   * Calculate estimated completion time for a fill request This method uses separate logic from
   * actual form filling to provide accurate estimates
   */
  public LocalDateTime calculateEstimatedCompletionTime(int submissionCount,
      LocalDateTime startDate, LocalDateTime endDate, boolean isHumanLike, int completedSurvey) {

    if (submissionCount <= 0 || completedSurvey >= submissionCount) {
      return startDate != null ? startDate : DateTimeUtil.now();
    }

    int remainingTasks = submissionCount - completedSurvey;

    // Always use current time as base for estimation to avoid past completion dates
    // Use DateTimeUtil.now() to ensure Vietnam timezone
    LocalDateTime base = DateTimeUtil.now();

    // Use separate estimation logic that doesn't affect actual form filling
    return calculateRealisticEstimate(remainingTasks, base, endDate, isHumanLike, completedSurvey);
  }

  /**
   * Ensure current slot has at least minRequired tasks when feasible by borrowing from later slots.
   * This is a light post-processing step that does not change min/max-gap runtime logic.
   */
  private void enforceMinForCurrentSlot(int[] perSlot, int currentIdx, List<TimeSlot> timeSlots,
      int totalTasks, LocalDateTime now, int minGapSeconds, int minRequired) {
    if (currentIdx < 0 || timeSlots.isEmpty() || minRequired <= 0)
      return;

    int already = perSlot[currentIdx];
    if (already >= minRequired)
      return;

    // Compute feasible capacity within the remaining time of the current slot
    TimeSlot cur = timeSlots.get(currentIdx);
    LocalDateTime start = now.isAfter(cur.getStart()) ? now : cur.getStart();
    long secondsLeft = Math.max(0, java.time.Duration.between(start, cur.getEnd()).getSeconds());
    int capacity = (minGapSeconds <= 0) ? Integer.MAX_VALUE : (int) (secondsLeft / minGapSeconds);
    if (capacity <= 0)
      return;

    int maxPossible = Math.min(capacity, totalTasks);
    int target = Math.min(minRequired, maxPossible);
    if (already >= target)
      return;

    int need = target - already;
    int n = timeSlots.size();

    // Borrow from forward slots, preferring the largest donors first
    while (need > 0) {
      int donor = -1;
      int maxCount = 0;
      for (int step = 1; step < n; step++) {
        int idx = (currentIdx + step) % n;
        if (perSlot[idx] > maxCount) {
          maxCount = perSlot[idx];
          donor = idx;
        }
      }

      if (donor == -1 || perSlot[donor] <= 1)
        break; // no more to borrow safely

      perSlot[donor]--;
      perSlot[currentIdx]++;
      need--;
    }
  }

  /**
   * Calculate realistic estimate based on actual observed timing patterns This is separate from the
   * actual form filling schedule logic
   */
  private LocalDateTime calculateRealisticEstimate(int remainingTasks, LocalDateTime base,
      LocalDateTime endDate, boolean isHumanLike, int completedSurvey) {

    if (isHumanLike) {
      // Human-like mode: 2-15 minutes average (7.5 min = 450 seconds)
      int avgDelaySeconds = remainingTasks > 1 ? (remainingTasks - 1) * 450 : 0;

      // Add 120-150 seconds buffer for human-like mode
      int humanBufferSeconds = 135; // 2 minutes 15 seconds buffer

      LocalDateTime estimate = base.plusSeconds(avgDelaySeconds + humanBufferSeconds);

      // Ensure estimate is always in the future (at least 1 minute from now)
      LocalDateTime now = DateTimeUtil.now();
      if (estimate.isBefore(now.plusMinutes(1))) {
        estimate = now.plusMinutes(1);
      }

      return estimate;
    } else {
      // Fast mode: Based on real data analysis with minimal buffer
      // Real observed timing: ~16 seconds average between forms
      // First form takes ~28 seconds to start, then ~16s intervals
      int firstFormDelay = 30; // Time to start first form (slightly higher for safety)
      int avgIntervalSeconds = 22; // Close to real 16s with small buffer
      int totalEstimateSeconds = firstFormDelay + (remainingTasks - 1) * avgIntervalSeconds;

      // Add minimal safety buffer (30 seconds)
      int safetyBufferSeconds = 30; // 30 seconds buffer

      LocalDateTime estimate = base.plusSeconds(totalEstimateSeconds + safetyBufferSeconds);

      // Ensure estimate is always in the future (at least 1 minute from now)
      LocalDateTime now = DateTimeUtil.now();
      if (estimate.isBefore(now.plusMinutes(1))) {
        estimate = now.plusMinutes(1);
      }

      return estimate;
    }
  }

  /**
   * Create human-like schedule with realistic patterns
   */
  private List<ScheduledTask> createHumanLikeSchedule(int submissionCount, ZonedDateTime start,
      ZonedDateTime end, int completedSurvey) {
    List<ScheduledTask> schedule = new ArrayList<>();

    // Create time slots with different probabilities
    List<TimeSlot> timeSlots = createHumanTimeSlots(start, end);

    // Determine current slot in VN timezone
    LocalDateTime now = DateTimeUtil.now();
    int currentIdx = -1;
    for (int i = 0; i < timeSlots.size(); i++) {
      TimeSlot s = timeSlots.get(i);
      if ((now.isAfter(s.getStart()) || now.isEqual(s.getStart())) && now.isBefore(s.getEnd())) {
        currentIdx = i;
        break;
      }
    }

    // If small job (<10) and we have a current slot, try to place all inside this slot
    if (submissionCount < 10 && currentIdx >= 0) {
      LocalDateTime cursor = now;
      long remainingSeconds =
          java.time.Duration.between(now, timeSlots.get(currentIdx).getEnd()).getSeconds();
      int remainingTasks = submissionCount;

      int minGap = Math.max(5, minGapHumanSeconds);
      int maxGap = Math.max(minGap, maxGapHumanSeconds);

      long normalNeed = (long) (remainingTasks - 1) * minGapHumanSeconds;
      int effectiveGap = normalNeed <= remainingSeconds ? minGapHumanSeconds
          : (int) Math.max(5, remainingSeconds / Math.max(1, (remainingTasks - 1)));

      for (int i = 0; i < remainingTasks; i++) {
        int delaySeconds;
        if (i == 0) {
          // CRITICAL FIX: First form of remaining surveys should always execute immediately
          delaySeconds = 0;
          schedule.add(new ScheduledTask(now, delaySeconds, completedSurvey + i));
          cursor = now;
        } else {
          int range = Math.max(0, maxGap - effectiveGap);
          int step = effectiveGap + (range > 0 ? random.nextInt(range + 1) : 0);
          cursor = cursor.plusSeconds(step);
          if (cursor.isAfter(timeSlots.get(currentIdx).getEnd())) {
            cursor = timeSlots.get(currentIdx).getEnd()
                .minusSeconds(Math.max(0, remainingTasks - i - 1));
          }
          schedule.add(new ScheduledTask(cursor, step, completedSurvey + i));
        }
      }
      log.info(
          "Scheduled {} tasks within current slot [{} - {}] with effectiveGap={}s (small-job mode)",
          remainingTasks, timeSlots.get(currentIdx).getStart(), timeSlots.get(currentIdx).getEnd(),
          effectiveGap);

      // CRITICAL FIX: Sort tasks by row_index to ensure proper order
      // This ensures row 3 always executes before row 6, regardless of execution time
      schedule.sort(java.util.Comparator.comparingInt(ScheduledTask::getRowIndex));

      return schedule;
    }

    // For larger jobs or when no current slot: prioritize current slot, then distribute by weights
    int[] perSlot = distributeCountsByWeight(timeSlots, submissionCount, Math.max(0, currentIdx));

    // Normalize for continuous coverage: ensure no gaps between first and last allocated slot
    normalizeContinuousCoverage(perSlot, currentIdx >= 0 ? currentIdx : 0, timeSlots.size());
    // Ensure current slot has a minimum number of tasks (> 2) when feasible
    enforceMinForCurrentSlot(perSlot, currentIdx, timeSlots, submissionCount, now,
        minGapHumanSeconds, 3);

    LocalDateTime lastExecutionTime = null;
    int produced = 0;
    int totalSlots = timeSlots.size();
    for (int stepIdx = 0; stepIdx < totalSlots; stepIdx++) {
      int slotIndex = currentIdx >= 0 ? (currentIdx + stepIdx) % totalSlots : stepIdx;
      TimeSlot slot = timeSlots.get(slotIndex);
      int count = perSlot[slotIndex];
      if (count <= 0)
        continue;

      // Start around a random time in the slot, but not before now for the current slot
      LocalDateTime cursor = slot.getRandomTimeInSlot();
      if (slotIndex == currentIdx && cursor.isBefore(now)) {
        cursor = now;
      }

      for (int j = 0; j < count; j++) {
        int delayRange = Math.max(0, maxGapHumanSeconds - minGapHumanSeconds);
        // CRITICAL FIX: First form of remaining surveys should always execute immediately
        // When resuming from QUEUED, the first remaining form should start immediately
        int stepSeconds;
        if (produced == 0) {
          stepSeconds = 0; // First form of remaining surveys: execute immediately
        } else {
          stepSeconds = minGapHumanSeconds + (delayRange > 0 ? random.nextInt(delayRange + 1) : 0);
        }

        if (lastExecutionTime != null) {
          LocalDateTime candidate = lastExecutionTime.plusSeconds(stepSeconds);
          if (candidate.isAfter(cursor)) {
            cursor = candidate;
          }
        }

        // CRITICAL FIX: For the first global task in current slot, always start immediately
        // When resuming from QUEUED, the first remaining form should start immediately
        if (produced == 0 && slotIndex == currentIdx) {
          // First form of remaining surveys: start immediately at current time
          cursor = now;
        }

        // Keep inside slot bounds and ensure continuity to next slot if overflow
        if (cursor.isAfter(slot.getEnd())) {
          cursor = slot.getEnd();
        }

        schedule.add(new ScheduledTask(cursor, stepSeconds, completedSurvey + produced));
        lastExecutionTime = cursor;
        produced++;
        // Nudge cursor forward slightly to avoid identical timestamps when random step=0
        cursor = cursor.plusSeconds(1);
      }
    }

    log.info("Human-like schedule (current-first, weighted) created with {} tasks across {} slots",
        schedule.size(), totalSlots);

    // CRITICAL FIX: Sort tasks by row_index to ensure proper order
    // This ensures row 3 always executes before row 6, regardless of execution time
    schedule.sort(java.util.Comparator.comparingInt(ScheduledTask::getRowIndex));

    return schedule;
  }

  /**
   * Normalizes slot distribution to ensure continuous coverage from first to last allocated slot.
   * Fills gaps by borrowing from the highest-count slots.
   */
  private void normalizeContinuousCoverage(int[] perSlot, int startIdx, int totalSlots) {
    // Find first and last non-zero slots starting from startIdx (forward only)
    int firstNonZero = -1;
    int lastNonZero = -1;

    for (int i = 0; i < totalSlots; i++) {
      int slotIdx = (startIdx + i) % totalSlots;
      if (perSlot[slotIdx] > 0) {
        if (firstNonZero == -1) {
          firstNonZero = slotIdx;
        }
        lastNonZero = slotIdx;
      }
    }

    if (firstNonZero == -1 || firstNonZero == lastNonZero) {
      return; // No slots or only one slot, nothing to normalize
    }

    // Fill gaps between first and last non-zero slots (forward direction only)
    for (int i = 0; i < totalSlots; i++) {
      int slotIdx = (startIdx + i) % totalSlots;

      // Check if this slot is between first and last (inclusive, forward direction)
      boolean isInRange = false;
      if (firstNonZero <= lastNonZero) {
        isInRange = (slotIdx >= firstNonZero && slotIdx <= lastNonZero);
      } else {
        // Wrapping case - we still want forward direction from startIdx
        int steps = 0;
        for (int j = 0; j < totalSlots; j++) {
          int checkIdx = (startIdx + j) % totalSlots;
          if (checkIdx == slotIdx) {
            steps = j;
            break;
          }
        }

        int firstSteps = 0, lastSteps = 0;
        for (int j = 0; j < totalSlots; j++) {
          int checkIdx = (startIdx + j) % totalSlots;
          if (checkIdx == firstNonZero)
            firstSteps = j;
          if (checkIdx == lastNonZero)
            lastSteps = j;
        }

        isInRange = (steps >= firstSteps && steps <= lastSteps);
      }

      if (isInRange && perSlot[slotIdx] == 0) {
        // Find slot with maximum count to borrow from
        int maxSlot = -1;
        int maxCount = 0;
        for (int j = 0; j < totalSlots; j++) {
          if (perSlot[j] > maxCount) {
            maxCount = perSlot[j];
            maxSlot = j;
          }
        }

        if (maxSlot != -1 && perSlot[maxSlot] > 1) {
          perSlot[maxSlot]--;
          perSlot[slotIdx] = 1;
        }
      }
    }
  }

  // Helper: distribute counts by slot weights, biasing the current slot slightly
  private int[] distributeCountsByWeight(List<TimeSlot> slots, int total, int currentIdx) {
    double totalWeight = 0.0;
    for (int i = 0; i < slots.size(); i++) {
      double w = slots.get(i).getWeight();
      if (i == currentIdx && slots.size() > 1) {
        w += 0.5; // small bias to current slot
      }
      totalWeight += w;
    }

    int[] result = new int[slots.size()];
    int assigned = 0;
    for (int i = 0; i < slots.size(); i++) {
      double w = slots.get(i).getWeight();
      if (i == currentIdx && slots.size() > 1) {
        w += 0.5;
      }
      int count = (int) Math.floor(total * (w / Math.max(1e-6, totalWeight)));
      result[i] = count;
      assigned += count;
    }

    int idx = currentIdx >= 0 ? currentIdx : 0;
    while (assigned < total) {
      result[idx]++;
      assigned++;
      idx = (idx + 1) % slots.size();
    }
    return result;
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
        delaySeconds = 1 + random.nextInt(2); // 1 or 2 seconds
      } else {
        delaySeconds = 0; // First form: no delay
      }

      // FIX: Sử dụng completedSurvey + i để tiếp tục từ vị trí đã dừng
      // Khi completedSurvey = 1: survey 2 = row 2, survey 3 = row 3, survey 4 = row 4...
      int rowIndex = completedSurvey + i;
      schedule.add(new ScheduledTask(currentTime, delaySeconds, rowIndex));

      // Add interval for next task
      long interval = intervalSeconds;
      // Cap max gap between planned tasks to 10 seconds in fast mode
      currentTime = currentTime.plusSeconds(Math.min(10, Math.max(1, interval)));

      // Don't exceed end date
      if (currentTime.isAfter(end.toLocalDateTime())) {
        currentTime = end.toLocalDateTime().minusSeconds(submissionCount - i - 1);
      }
    }

    // CRITICAL FIX: Sort tasks by row_index to ensure proper order
    // This ensures row 3 always executes before row 6, regardless of execution time
    schedule.sort(java.util.Comparator.comparingInt(ScheduledTask::getRowIndex));

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

      // CRITICAL FIX: Calculate cumulative delay for proper scheduling
      if (isHumanLike) {
        if (completedSurvey == 0 && i == 0) {
          delaySeconds = 0; // First form: execute immediately
          currentTime = DateTimeUtil.now(); // Set to current time for immediate execution
          log.debug("Human-like mode: First form with immediate execution (delay: {} seconds)",
              delaySeconds);
        } else {
          // Subsequent forms: distributed delays using configurable range
          int delayRange = maxGapHumanSeconds - minGapHumanSeconds;
          delaySeconds = minGapHumanSeconds + random.nextInt(delayRange + 1);
          // CRITICAL FIX: Add delay to current time, not from now()
          currentTime = currentTime.plusSeconds(delaySeconds);
          log.debug(
              "Human-like mode: Form {} with delay of {} seconds ({} minutes), scheduled at {} [range: {}-{} seconds]",
              i + 1, delaySeconds, delaySeconds / 60, currentTime, minGapHumanSeconds,
              maxGapHumanSeconds);
        }
      } else {
        // Fast mode: minimal delays
        if (i == 0) {
          delaySeconds = 0; // First form: no delay
          currentTime = DateTimeUtil.now(); // Set to current time for immediate execution
          log.debug("Fast mode: First form with immediate execution (delay: {} seconds)",
              delaySeconds);
        } else {
          delaySeconds = 1 + random.nextInt(2); // 1 or 2 seconds
          // CRITICAL FIX: Add delay to current time, not from now()
          currentTime = currentTime.plusSeconds(delaySeconds);
          log.debug("Fast mode: Form {} with {} second delay, scheduled at {}", i + 1, delaySeconds,
              currentTime);
        }
      }

      // FIX: Sử dụng completedSurvey + i để tiếp tục từ vị trí đã dừng
      // Khi completedSurvey = 1: survey 2 = row 2, survey 3 = row 3, survey 4 = row 4...
      int rowIndex = completedSurvey + i;
      schedule.add(new ScheduledTask(currentTime, delaySeconds, rowIndex));
    }

    // CRITICAL FIX: Sort tasks by row_index to ensure proper order
    // This ensures row 3 always executes before row 6, regardless of execution time
    schedule.sort(java.util.Comparator.comparingInt(ScheduledTask::getRowIndex));

    log.info("Created {} immediate execution tasks starting at {} (sorted by row_index)",
        schedule.size(), startDate);
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
          delaySeconds = 1;
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
      // For fast mode, assume ~20 seconds per form to match 5-35s spacing
      adjustedDelaySeconds = 3;
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
