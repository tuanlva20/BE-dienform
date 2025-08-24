package com.dienform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QueueConfig {

  public static class QueueProperties {
    private int maxSize = 100;
    private long checkInterval = 30000; // 30 seconds
    private int maxRetries = 3;
    private long retryDelay = 5000; // 5 seconds

    // Getters and setters
    public int getMaxSize() {
      return maxSize;
    }

    public void setMaxSize(int maxSize) {
      this.maxSize = maxSize;
    }

    public long getCheckInterval() {
      return checkInterval;
    }

    public void setCheckInterval(long checkInterval) {
      this.checkInterval = checkInterval;
    }

    public int getMaxRetries() {
      return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
      this.maxRetries = maxRetries;
    }

    public long getRetryDelay() {
      return retryDelay;
    }

    public void setRetryDelay(long retryDelay) {
      this.retryDelay = retryDelay;
    }
  }

  @Bean
  @ConfigurationProperties(prefix = "queue")
  public QueueProperties queueProperties() {
    return new QueueProperties();
  }
}
