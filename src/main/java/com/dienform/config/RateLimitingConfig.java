package com.dienform.config;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@Configuration
@Component
public class RateLimitingConfig {

  private static class RequestCounter {
    private int requestCount;
    private LocalDateTime lastResetTime;

    public RequestCounter() {
      this.requestCount = 0;
      this.lastResetTime = LocalDateTime.now();
    }

    public void increment() {
      this.requestCount++;
    }

    public void reset(LocalDateTime resetTime) {
      this.requestCount = 0;
      this.lastResetTime = resetTime;
    }

    public int getRequestCount() {
      return requestCount;
    }

    public LocalDateTime getLastResetTime() {
      return lastResetTime;
    }
  }

  private final ConcurrentHashMap<String, RequestCounter> requestCounters = new ConcurrentHashMap<>();

  private final int MAX_REQUESTS_PER_MINUTE = 10;

  public boolean isAllowed(String clientIp) {
    RequestCounter counter = requestCounters.computeIfAbsent(clientIp, k -> new RequestCounter());

    LocalDateTime now = LocalDateTime.now();

    // Reset counter if more than 1 minute has passed
    if (counter.getLastResetTime().until(now, ChronoUnit.MINUTES) >= 1) {
      counter.reset(now);
    }

    // Check if limit exceeded
    if (counter.getRequestCount() >= MAX_REQUESTS_PER_MINUTE) {
      return false;
    }

    counter.increment();
    return true;
  }

  public long getTimeUntilReset(String clientIp) {
    RequestCounter counter = requestCounters.get(clientIp);
    if (counter == null) {
      return 0;
    }

    LocalDateTime nextReset = counter.getLastResetTime().plusMinutes(1);
    return LocalDateTime.now().until(nextReset, ChronoUnit.SECONDS);
  }
}