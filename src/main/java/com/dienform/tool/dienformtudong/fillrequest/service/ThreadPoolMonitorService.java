package com.dienform.tool.dienformtudong.fillrequest.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

/**
 * Service để monitor thread pool và alert khi có vấn đề
 */
@Service
@Slf4j
public class ThreadPoolMonitorService {

  /**
   * Thread pool statistics
   */
  public static class ThreadPoolStats {
    public static class Builder {
      private int humanActive;
      private int humanQueueSize;
      private long humanCompleted;
      private int humanCorePoolSize;
      private int fastActive;
      private int fastQueueSize;
      private long fastCompleted;
      private int fastCorePoolSize;

      public Builder humanActive(int humanActive) {
        this.humanActive = humanActive;
        return this;
      }

      public Builder humanQueueSize(int humanQueueSize) {
        this.humanQueueSize = humanQueueSize;
        return this;
      }

      public Builder humanCompleted(long humanCompleted) {
        this.humanCompleted = humanCompleted;
        return this;
      }

      public Builder humanCorePoolSize(int humanCorePoolSize) {
        this.humanCorePoolSize = humanCorePoolSize;
        return this;
      }

      public Builder fastActive(int fastActive) {
        this.fastActive = fastActive;
        return this;
      }

      public Builder fastQueueSize(int fastQueueSize) {
        this.fastQueueSize = fastQueueSize;
        return this;
      }

      public Builder fastCompleted(long fastCompleted) {
        this.fastCompleted = fastCompleted;
        return this;
      }

      public Builder fastCorePoolSize(int fastCorePoolSize) {
        this.fastCorePoolSize = fastCorePoolSize;
        return this;
      }

      public ThreadPoolStats build() {
        return new ThreadPoolStats(humanActive, humanQueueSize, humanCompleted, humanCorePoolSize,
            fastActive, fastQueueSize, fastCompleted, fastCorePoolSize);
      }
    }

    public static Builder builder() {
      return new Builder();
    }

    private final int humanActive;
    private final int humanQueueSize;
    private final long humanCompleted;
    private final int humanCorePoolSize;
    private final int fastActive;
    private final int fastQueueSize;

    private final long fastCompleted;

    private final int fastCorePoolSize;

    public ThreadPoolStats(int humanActive, int humanQueueSize, long humanCompleted,
        int humanCorePoolSize, int fastActive, int fastQueueSize, long fastCompleted,
        int fastCorePoolSize) {
      this.humanActive = humanActive;
      this.humanQueueSize = humanQueueSize;
      this.humanCompleted = humanCompleted;
      this.humanCorePoolSize = humanCorePoolSize;
      this.fastActive = fastActive;
      this.fastQueueSize = fastQueueSize;
      this.fastCompleted = fastCompleted;
      this.fastCorePoolSize = fastCorePoolSize;
    }

    // Getters
    public int getHumanActive() {
      return humanActive;
    }

    public int getHumanQueueSize() {
      return humanQueueSize;
    }

    public long getHumanCompleted() {
      return humanCompleted;
    }

    public int getHumanCorePoolSize() {
      return humanCorePoolSize;
    }

    public int getFastActive() {
      return fastActive;
    }

    public int getFastQueueSize() {
      return fastQueueSize;
    }

    public long getFastCompleted() {
      return fastCompleted;
    }

    public int getFastCorePoolSize() {
      return fastCorePoolSize;
    }
  }

  @Autowired
  private ThreadPoolManager threadPoolManager;

  /**
   * Monitor thread pool status mỗi 10 giây
   */
  @Scheduled(fixedRate = 10000) // Every 10 seconds
  public void monitorThreadPools() {
    try {
      ExecutorService humanExecutorService = threadPoolManager.getHumanLikeExecutor();
      ExecutorService fastExecutorService = threadPoolManager.getFastModeExecutor();

      // Cast to ThreadPoolExecutor for monitoring
      ThreadPoolExecutor humanExecutor = (ThreadPoolExecutor) humanExecutorService;
      ThreadPoolExecutor fastExecutor = (ThreadPoolExecutor) fastExecutorService;

      // Monitor human-like executor
      int humanActive = humanExecutor.getActiveCount();
      int humanQueueSize = humanExecutor.getQueue().size();
      long humanCompleted = humanExecutor.getCompletedTaskCount();

      // Monitor fast-mode executor
      int fastActive = fastExecutor.getActiveCount();
      int fastQueueSize = fastExecutor.getQueue().size();
      long fastCompleted = fastExecutor.getCompletedTaskCount();

      // Log status
      log.info(
          "Thread Pool Status - Human: Active={}, Queue={}, Completed={} | Fast: Active={}, Queue={}, Completed={}",
          humanActive, humanQueueSize, humanCompleted, fastActive, fastQueueSize, fastCompleted);

      // Alert if queue is getting full
      if (humanQueueSize > 80) {
        log.warn("HUMAN POOL ALERT: Queue is getting full - {} tasks in queue", humanQueueSize);
      }

      if (fastQueueSize > 80) {
        log.warn("FAST POOL ALERT: Queue is getting full - {} tasks in queue", fastQueueSize);
      }

      // Alert if all threads are busy
      if (humanActive >= humanExecutor.getCorePoolSize() && humanQueueSize > 0) {
        log.warn("HUMAN POOL ALERT: All threads busy, {} tasks waiting in queue", humanQueueSize);
      }

      if (fastActive >= fastExecutor.getCorePoolSize() && fastQueueSize > 0) {
        log.warn("FAST POOL ALERT: All threads busy, {} tasks waiting in queue", fastQueueSize);
      }

    } catch (Exception e) {
      log.error("Error monitoring thread pools: {}", e.getMessage(), e);
    }
  }

  /**
   * Record timeout event
   */
  public void recordTimeout(String fillRequestId, int totalForms, int completedForms,
      boolean isHumanLike) {
    double successRate = (double) completedForms / totalForms;

    log.error("TIMEOUT ALERT: FillRequest {} ({}) completed {}/{} forms ({}% success rate)",
        fillRequestId, isHumanLike ? "HUMAN" : "FAST", completedForms, totalForms,
        (int) (successRate * 100));

    if (successRate < 0.5) {
      log.error("CRITICAL: Low success rate {}% for request {} - {} mode",
          (int) (successRate * 100), fillRequestId, isHumanLike ? "HUMAN" : "FAST");
    }

    if (successRate < 0.1) {
      log.error("EMERGENCY: Very low success rate {}% for request {} - {} mode",
          (int) (successRate * 100), fillRequestId, isHumanLike ? "HUMAN" : "FAST");
    }
  }

  /**
   * Get thread pool statistics
   */
  public ThreadPoolStats getThreadPoolStats() {
    ExecutorService humanExecutorService = threadPoolManager.getHumanLikeExecutor();
    ExecutorService fastExecutorService = threadPoolManager.getFastModeExecutor();

    // Cast to ThreadPoolExecutor for monitoring
    ThreadPoolExecutor humanExecutor = (ThreadPoolExecutor) humanExecutorService;
    ThreadPoolExecutor fastExecutor = (ThreadPoolExecutor) fastExecutorService;

    return ThreadPoolStats.builder().humanActive(humanExecutor.getActiveCount())
        .humanQueueSize(humanExecutor.getQueue().size())
        .humanCompleted(humanExecutor.getCompletedTaskCount())
        .humanCorePoolSize(humanExecutor.getCorePoolSize())
        .fastActive(fastExecutor.getActiveCount()).fastQueueSize(fastExecutor.getQueue().size())
        .fastCompleted(fastExecutor.getCompletedTaskCount())
        .fastCorePoolSize(fastExecutor.getCorePoolSize()).build();
  }

  /**
   * Check if system is overloaded
   */
  public boolean isSystemOverloaded() {
    ThreadPoolStats stats = getThreadPoolStats();

    // Check if both pools are heavily loaded
    boolean humanOverloaded =
        stats.getHumanQueueSize() > 50 && stats.getHumanActive() >= stats.getHumanCorePoolSize();
    boolean fastOverloaded =
        stats.getFastQueueSize() > 50 && stats.getFastActive() >= stats.getFastCorePoolSize();

    return humanOverloaded || fastOverloaded;
  }
}
