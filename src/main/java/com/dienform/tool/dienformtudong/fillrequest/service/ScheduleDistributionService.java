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

      // EXACT LOGIC FROM API /form/{formId}/fill-request (GoogleFormServiceImpl)
      int delaySeconds;
      if (completedSurvey == 0 && i == 0) {
        delaySeconds = 36; // First form: minimal delay
      } else {
        delaySeconds = 36 + random.nextInt(364); // 36-399 seconds
      }
      log.debug("Human-like mode: Form {} with delay of {} seconds", i + 1, delaySeconds);

      schedule.add(new ScheduledTask(executionTime, delaySeconds, i));
    }

    // Sort by execution time and recalculate delays based on actual execution times
    schedule.sort((a, b) -> a.getExecutionTime().compareTo(b.getExecutionTime()));

    // Recalculate delays based on EXACT LOGIC FROM API /form/{formId}/fill-request
    for (int i = 0; i < schedule.size(); i++) {
      ScheduledTask task = schedule.get(i);
      int newDelaySeconds;

      // EXACT LOGIC FROM API /form/{formId}/fill-request (GoogleFormServiceImpl)
      if (completedSurvey == 0 && i == 0) {
        newDelaySeconds = 36; // First form: minimal delay
      } else {
        newDelaySeconds = 36 + random.nextInt(364); // 36-399 seconds
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
      // EXACT LOGIC FROM API /form/{formId}/fill-request (GoogleFormServiceImpl)
      int delaySeconds;
      if (i > 0) {
        delaySeconds = 1; // Fast mode: 1 second between forms
      } else {
        delaySeconds = 0; // First form: no delay
      }

      schedule.add(new ScheduledTask(currentTime, delaySeconds, i));

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

      // EXACT LOGIC FROM API /form/{formId}/fill-request (GoogleFormServiceImpl)
      if (isHumanLike) {
        if (completedSurvey == 0 && i == 0) {
          delaySeconds = 36; // First form: minimal delay
        } else {
          delaySeconds = 36 + random.nextInt(364); // 36-399 seconds
        }
        log.debug("Human-like mode: Form {} with delay of {} seconds", i + 1, delaySeconds);

        // Set execution time in the future for human-like behavior
        currentTime = currentTime.plusSeconds(delaySeconds);
        log.debug("Human-like task {} scheduled at {} with {} seconds delay", i, currentTime,
            delaySeconds);
      } else if (i > 0) {
        delaySeconds = 1; // Fast mode: 1 second between forms
        log.debug("Fast mode: 1 second delay between forms");

        // All tasks start immediately at current time
        currentTime = LocalDateTime.now();
        log.debug("Fast task {} scheduled immediately at {} (delay: {} seconds)", i, currentTime,
            delaySeconds);
      } else {
        delaySeconds = 0; // First form: no delay
        log.debug("Fast mode: First form with no delay");

        // All tasks start immediately at current time
        currentTime = LocalDateTime.now();
        log.debug("Fast task {} scheduled immediately at {} (delay: {} seconds)", i, currentTime,
            delaySeconds);
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
