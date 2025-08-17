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
    List<ScheduledTask> schedule = new ArrayList<>();

    if (endDate == null) {
      // If no end date, schedule all tasks starting from start date
      return distributeWithoutEndDate(submissionCount, startDate, isHumanLike);
    }

    // Convert to Vietnam timezone for realistic scheduling
    ZonedDateTime startZoned = startDate.atZone(VIETNAM_TIMEZONE);
    ZonedDateTime endZoned = endDate.atZone(VIETNAM_TIMEZONE);

    log.info("Distributing {} submissions from {} to {} (Vietnam time), humanLike: {}",
        submissionCount, startZoned, endZoned, isHumanLike);

    if (isHumanLike) {
      schedule = createHumanLikeSchedule(submissionCount, startZoned, endZoned);
    } else {
      schedule = createFastSchedule(submissionCount, startZoned, endZoned);
    }

    return schedule;
  }

  /**
   * Create human-like schedule with realistic patterns
   */
  private List<ScheduledTask> createHumanLikeSchedule(int submissionCount, ZonedDateTime start,
      ZonedDateTime end) {
    List<ScheduledTask> schedule = new ArrayList<>();

    long totalMinutes = java.time.Duration.between(start, end).toMinutes();

    // Create time slots with different probabilities
    List<TimeSlot> timeSlots = createHumanTimeSlots(start, end);

    for (int i = 0; i < submissionCount; i++) {
      TimeSlot selectedSlot = selectWeightedTimeSlot(timeSlots);
      LocalDateTime executionTime = selectedSlot.getRandomTimeInSlot();

      // Random delay between 36-399 seconds for human-like behavior
      int delaySeconds = 36 + random.nextInt(364); // 36-399 seconds

      // Occasionally add longer delays (1-5 minutes) to simulate breaks
      if (random.nextDouble() < 0.1) { // 10% chance
        delaySeconds += 60 + random.nextInt(240); // Add 1-5 minutes
      }

      schedule.add(new ScheduledTask(executionTime, delaySeconds, i));
    }

    // Sort by execution time
    schedule.sort((a, b) -> a.getExecutionTime().compareTo(b.getExecutionTime()));

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
      boolean isHumanLike) {
    List<ScheduledTask> schedule = new ArrayList<>();
    LocalDateTime currentTime = startDate;

    for (int i = 0; i < submissionCount; i++) {
      int delaySeconds;

      if (isHumanLike) {
        // Human-like delays: 36-399 seconds, with occasional longer breaks
        delaySeconds = 36 + random.nextInt(364);
        if (random.nextDouble() < 0.15) { // 15% chance for longer break
          delaySeconds += 120 + random.nextInt(300); // Add 2-7 minutes
        }
      } else {
        // Fast filling: 1-3 seconds
        delaySeconds = 1 + random.nextInt(3);
      }

      schedule.add(new ScheduledTask(currentTime, delaySeconds, i));

      // Calculate next execution time
      currentTime = currentTime.plusSeconds(delaySeconds);
    }

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
