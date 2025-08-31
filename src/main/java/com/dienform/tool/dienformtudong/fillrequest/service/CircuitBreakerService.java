package com.dienform.tool.dienformtudong.fillrequest.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

/**
 * Service để implement circuit breaker pattern cho việc skip failed forms
 */
@Service
@Slf4j
public class CircuitBreakerService {

  /**
   * Thống kê circuit breaker
   */
  public static class CircuitBreakerStats {
    public static class Builder {
      private int totalForms;
      private int openCircuits;
      private int maxFailures;
      private long timeoutSeconds;
      private boolean skipFailedForms;
      private boolean continueOnError;

      public Builder totalForms(int totalForms) {
        this.totalForms = totalForms;
        return this;
      }

      public Builder openCircuits(int openCircuits) {
        this.openCircuits = openCircuits;
        return this;
      }

      public Builder maxFailures(int maxFailures) {
        this.maxFailures = maxFailures;
        return this;
      }

      public Builder timeoutSeconds(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
        return this;
      }

      public Builder skipFailedForms(boolean skipFailedForms) {
        this.skipFailedForms = skipFailedForms;
        return this;
      }

      public Builder continueOnError(boolean continueOnError) {
        this.continueOnError = continueOnError;
        return this;
      }

      public CircuitBreakerStats build() {
        return new CircuitBreakerStats(totalForms, openCircuits, maxFailures, timeoutSeconds,
            skipFailedForms, continueOnError);
      }
    }

    public static Builder builder() {
      return new Builder();
    }

    private final int totalForms;
    private final int openCircuits;
    private final int maxFailures;
    private final long timeoutSeconds;

    private final boolean skipFailedForms;

    private final boolean continueOnError;

    public CircuitBreakerStats(int totalForms, int openCircuits, int maxFailures,
        long timeoutSeconds, boolean skipFailedForms, boolean continueOnError) {
      this.totalForms = totalForms;
      this.openCircuits = openCircuits;
      this.maxFailures = maxFailures;
      this.timeoutSeconds = timeoutSeconds;
      this.skipFailedForms = skipFailedForms;
      this.continueOnError = continueOnError;
    }

    // Getters
    public int getTotalForms() {
      return totalForms;
    }

    public int getOpenCircuits() {
      return openCircuits;
    }

    public int getMaxFailures() {
      return maxFailures;
    }

    public long getTimeoutSeconds() {
      return timeoutSeconds;
    }

    public boolean isSkipFailedForms() {
      return skipFailedForms;
    }

    public boolean isContinueOnError() {
      return continueOnError;
    }
  }

  @Value("${google.form.circuit-breaker.max-failures:3}")
  private int maxFailures;

  @Value("${google.form.circuit-breaker.timeout-seconds:60}")
  private long timeoutSeconds;

  @Value("${google.form.circuit-breaker.skip-failed-forms:true}")
  private boolean skipFailedForms;

  @Value("${google.form.circuit-breaker.continue-on-error:true}")
  private boolean continueOnError;

  // Track failure count per form ID
  private final Map<String, AtomicInteger> failureCount = new ConcurrentHashMap<>();

  // Track last failure time per form ID
  private final Map<String, Long> lastFailureTime = new ConcurrentHashMap<>();

  /**
   * Kiểm tra xem form có bị circuit breaker block không
   */
  public boolean isOpen(String formId) {
    if (!skipFailedForms) {
      return false; // Circuit breaker disabled
    }

    AtomicInteger failures = failureCount.get(formId);
    if (failures == null) {
      return false; // No failures recorded
    }

    int currentFailures = failures.get();
    if (currentFailures < maxFailures) {
      return false; // Not enough failures to open circuit
    }

    // Check if timeout has passed to allow retry
    Long lastFailure = lastFailureTime.get(formId);
    if (lastFailure != null) {
      long timeSinceLastFailure = System.currentTimeMillis() - lastFailure;
      if (timeSinceLastFailure > timeoutSeconds * 1000) {
        // Reset circuit breaker after timeout
        log.info("Circuit breaker timeout passed for form {}, resetting", formId);
        reset(formId);
        return false;
      }
    }

    log.warn("Circuit breaker OPEN for form {} ({} failures)", formId, currentFailures);
    return true;
  }

  /**
   * Ghi nhận thành công cho form
   */
  public void recordSuccess(String formId) {
    if (skipFailedForms) {
      reset(formId);
      log.debug("Circuit breaker reset for form {} after success", formId);
    }
  }

  /**
   * Ghi nhận thất bại cho form
   */
  public void recordFailure(String formId) {
    if (!skipFailedForms) {
      return; // Circuit breaker disabled
    }

    AtomicInteger failures = failureCount.computeIfAbsent(formId, k -> new AtomicInteger(0));
    int currentFailures = failures.incrementAndGet();
    lastFailureTime.put(formId, System.currentTimeMillis());

    log.warn("Form {} failure recorded: {}/{} failures", formId, currentFailures, maxFailures);

    if (currentFailures >= maxFailures) {
      log.error("Circuit breaker TRIPPED for form {} after {} failures", formId, currentFailures);
    }
  }

  /**
   * Reset circuit breaker cho form
   */
  public void reset(String formId) {
    failureCount.remove(formId);
    lastFailureTime.remove(formId);
    log.debug("Circuit breaker reset for form {}", formId);
  }

  /**
   * Reset tất cả circuit breakers
   */
  public void resetAll() {
    failureCount.clear();
    lastFailureTime.clear();
    log.info("All circuit breakers reset");
  }

  /**
   * Lấy thống kê circuit breaker
   */
  public CircuitBreakerStats getStats() {
    int totalForms = failureCount.size();
    int openCircuits = (int) failureCount.values().stream().mapToInt(AtomicInteger::get)
        .filter(failures -> failures >= maxFailures).count();

    return CircuitBreakerStats.builder().totalForms(totalForms).openCircuits(openCircuits)
        .maxFailures(maxFailures).timeoutSeconds(timeoutSeconds).skipFailedForms(skipFailedForms)
        .continueOnError(continueOnError).build();
  }
}

