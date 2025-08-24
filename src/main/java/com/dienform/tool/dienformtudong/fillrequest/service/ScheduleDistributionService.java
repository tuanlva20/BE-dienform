package com.dienform.tool.dienformtudong.fillrequest.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ScheduleDistributionService {

  public static class ScheduledTask {
    private final LocalDateTime executionTime;
    private final int delaySeconds;
    private final int rowIndex;

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
      schedule = createFastSchedule(submissionCount, startZoned, endZoned);
    }

    return schedule;
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

      // Calculate delay based on new logic: first form always immediate, others based on
      // completedSurvey
      int delaySeconds;
      if (i == 0) {
        // First form always has 0 delay for immediate execution
        delaySeconds = 0;
        log.debug("First task scheduled with 0 delay (immediate execution)");
      } else {
        delaySeconds = (2 + random.nextInt(14)) * 60;; // 2-15 minutes
        log.debug("Task {} scheduled with {} seconds delay (10-30 seconds)", i, delaySeconds);
      }

      schedule.add(new ScheduledTask(executionTime, delaySeconds, i));
    }

    // Sort by execution time and recalculate delays based on actual execution times
    schedule.sort((a, b) -> a.getExecutionTime().compareTo(b.getExecutionTime()));

    // Recalculate delays based on new logic and execution order
    for (int i = 0; i < schedule.size(); i++) {
      ScheduledTask task = schedule.get(i);
      int newDelaySeconds;

      if (i == 0) {
        // First form always has 0 delay for immediate execution
        newDelaySeconds = 0;
      } else if (completedSurvey > 0) {
        // When completedSurvey > 0, subsequent forms have 2-15 minutes delay
        newDelaySeconds = (2 + random.nextInt(14)) * 60; // 2-15 minutes in seconds
      } else {
        // When completedSurvey = 0, subsequent forms have 2-15 minutes delay
        newDelaySeconds = (2 + random.nextInt(14)) * 60; // 2-15 minutes in seconds
      }

      // Create new task with updated delay
      schedule.set(i,
          new ScheduledTask(task.getExecutionTime(), newDelaySeconds, task.getRowIndex()));
    }

    return schedule;
  }

  /**
   * Create fast schedule for non-human-like filling
   */
  private List<ScheduledTask> createFastSchedule(int submissionCount, ZonedDateTime start,
      ZonedDateTime end) {
    List<ScheduledTask> schedule = new ArrayList<>();

    long totalSeconds = java.time.Duration.between(start, end).getSeconds();
    long intervalSeconds = totalSeconds / submissionCount;

    // Minimum interval of 1 second, maximum interval calculated
    intervalSeconds = Math.max(1, intervalSeconds);

    LocalDateTime currentTime = start.toLocalDateTime();

    for (int i = 0; i < submissionCount; i++) {
      // Random delay between 1-3 seconds
      int delaySeconds = 1 + random.nextInt(3);

      schedule.add(new ScheduledTask(currentTime, delaySeconds, i));

      // Add random interval for next task
      long randomInterval = intervalSeconds + random.nextInt(10) - 5; // ±5 seconds variation
      currentTime = currentTime.plusSeconds(Math.max(1, randomInterval));

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

      if (isHumanLike) {
        // New logic: first form always immediate, others based on completedSurvey
        if (i == 0) {
          // First form always has 0 delay for immediate execution
          delaySeconds = 0;
          log.debug("First task scheduled with 0 delay (immediate execution)");
        } else if (completedSurvey > 0) {
          // When completedSurvey > 0, subsequent forms have 2-15 minutes delay
          delaySeconds = (2 + random.nextInt(14)) * 60; // 2-15 minutes in seconds
          log.debug("Task {} scheduled with {} seconds delay (2-15 minutes)", i, delaySeconds);
        } else {
          // When completedSurvey = 0, subsequent forms have 10-30 seconds delay
          delaySeconds = 10 + random.nextInt(21); // 10-30 seconds
          log.debug("Task {} scheduled with {} seconds delay (10-30 seconds)", i, delaySeconds);
        }

        // Set execution time in the future for human-like behavior
        currentTime = currentTime.plusSeconds(delaySeconds);
        log.debug("Human-like task {} scheduled at {} with {} seconds delay", i, currentTime,
            delaySeconds);
      } else {
        // NON-HUMAN-LIKE: All tasks execute immediately, no delays
        // Thread pool will handle queuing automatically
        delaySeconds = 0; // No additional delay
        currentTime = startDate; // All tasks start at the same time
        log.debug("Fast task {} scheduled immediately at {} (no delay)", i, currentTime);
      }

      schedule.add(new ScheduledTask(currentTime, delaySeconds, i));
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
}
