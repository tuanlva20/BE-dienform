package com.dienform.tool.dienformtudong.fillrequest.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages separate thread pools for human and non-human fill requests with adaptive resizing.
 */
@Service
@Slf4j
public class ThreadPoolManager {

  @Value("${google.form.thread-pool-size:2}")
  private int threadPoolSize;

  @Value("${google.form.pool.human.min:1}")
  private int humanMin;

  @Value("${google.form.pool.fast.min:1}")
  private int fastMin;

  @Autowired
  private FillRequestRepository fillRequestRepository;

  private ThreadPoolExecutor humanLikeExecutor;
  private ThreadPoolExecutor fastModeExecutor;

  @PostConstruct
  public void init() {
    int humanThreads = Math.max(humanMin, threadPoolSize / 2);
    int fastThreads = Math.max(fastMin, threadPoolSize - humanThreads);

    // Ensure sum = threadPoolSize and respect minimums
    if (humanThreads + fastThreads != threadPoolSize) {
      fastThreads = Math.max(fastMin, threadPoolSize - humanThreads);
    }
    if (humanThreads < humanMin)
      humanThreads = humanMin;
    if (fastThreads < fastMin)
      fastThreads = fastMin;

    // Final adjustment to ensure sum equals threadPoolSize
    if (humanThreads + fastThreads != threadPoolSize) {
      if (humanThreads > humanMin) {
        humanThreads = threadPoolSize - fastThreads;
      } else if (fastThreads > fastMin) {
        fastThreads = threadPoolSize - humanThreads;
      }
    }

    log.info("ThreadPoolManager: Initializing with {} total threads ({} human, {} fast)",
        threadPoolSize, humanThreads, fastThreads);

    humanLikeExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(humanThreads);
    fastModeExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(fastThreads);

    // Set keep alive time before enabling core thread timeout
    humanLikeExecutor.setKeepAliveTime(30, TimeUnit.SECONDS);
    fastModeExecutor.setKeepAliveTime(30, TimeUnit.SECONDS);
    humanLikeExecutor.allowCoreThreadTimeOut(true);
    fastModeExecutor.allowCoreThreadTimeOut(true);

    log.info("ThreadPoolManager: Thread pools initialized successfully");
  }

  @PreDestroy
  public void cleanup() {
    log.info("ThreadPoolManager: Shutting down thread pools...");
    shutdownExecutorService(humanLikeExecutor, "Human-like");
    shutdownExecutorService(fastModeExecutor, "Fast-mode");
  }

  /** Get executor for human-like requests */
  public ExecutorService getHumanLikeExecutor() {
    return humanLikeExecutor;
  }

  /** Get executor for fast mode requests */
  public ExecutorService getFastModeExecutor() {
    return fastModeExecutor;
  }

  /** Get appropriate executor based on human-like flag */
  public ExecutorService getExecutor(boolean isHumanLike) {
    return isHumanLike ? humanLikeExecutor : fastModeExecutor;
  }

  /** Log thread pool status for debugging */
  public void logThreadPoolStatus(String context) {
    if (humanLikeExecutor != null) {
      log.debug("HUMAN POOL [{}]: Active={}, Core={}, Max={}, Queue={}, Completed={}", context,
          humanLikeExecutor.getActiveCount(), humanLikeExecutor.getCorePoolSize(),
          humanLikeExecutor.getMaximumPoolSize(), humanLikeExecutor.getQueue().size(),
          humanLikeExecutor.getCompletedTaskCount());
    }
    if (fastModeExecutor != null) {
      log.debug("FAST  POOL [{}]: Active={}, Core={}, Max={}, Queue={}, Completed={}", context,
          fastModeExecutor.getActiveCount(), fastModeExecutor.getCorePoolSize(),
          fastModeExecutor.getMaximumPoolSize(), fastModeExecutor.getQueue().size(),
          fastModeExecutor.getCompletedTaskCount());
    }
  }

  /**
   * Periodically rebalance pool sizes based on current queued requests per type. Ensures at least
   * one thread per type; does not block delayed submissions.
   */
  @Scheduled(fixedDelayString = "${google.form.pool.rebalance-ms:5000}")
  public void rebalancePools() {
    try {
      // Count both QUEUED and IN_PROCESS requests for more accurate demand calculation
      long humanQueued =
          fillRequestRepository.countByStatusAndHumanLike(FillRequestStatusEnum.QUEUED, true);
      long humanInProcess =
          fillRequestRepository.countByStatusAndHumanLike(FillRequestStatusEnum.IN_PROCESS, true);
      long fastQueued =
          fillRequestRepository.countByStatusAndHumanLike(FillRequestStatusEnum.QUEUED, false);
      long fastInProcess =
          fillRequestRepository.countByStatusAndHumanLike(FillRequestStatusEnum.IN_PROCESS, false);

      // Add executor queue sizes to get total backlog
      long humanQueueSize = humanLikeExecutor != null ? humanLikeExecutor.getQueue().size() : 0;
      long fastQueueSize = fastModeExecutor != null ? fastModeExecutor.getQueue().size() : 0;

      // Calculate total demand including active requests and queued tasks
      long humanTotal = humanQueued + humanInProcess + humanQueueSize;
      long fastTotal = fastQueued + fastInProcess + fastQueueSize;
      long total = humanTotal + fastTotal;

      if (total <= 0) {
        log.debug("Rebalance skipped: no active requests or queued tasks");
        return;
      }

      int minHuman = Math.max(0, humanMin); // Allow 0 for human pool
      int minFast = Math.max(1, fastMin); // Always keep at least 1 fast thread

      // Distribute threads based on total demand (QUEUED + IN_PROCESS + queue size)
      int desiredHuman = Math.max(minHuman,
          (int) Math.round((double) threadPoolSize * ((double) humanTotal / (double) total)));
      int desiredFast = Math.max(minFast, threadPoolSize - desiredHuman);

      // Ensure at least minimum per type and sum equals threadPoolSize
      if (desiredHuman < minHuman)
        desiredHuman = minHuman;
      if (desiredFast < minFast)
        desiredFast = minFast;

      // Ensure sum equals threadPoolSize
      if (desiredHuman + desiredFast != threadPoolSize) {
        // If human pool can be reduced, reduce it
        if (desiredHuman > minHuman) {
          desiredHuman = threadPoolSize - desiredFast;
        } else if (desiredFast > minFast) {
          desiredFast = threadPoolSize - desiredHuman;
        }
      }

      boolean changed = false;
      if (humanLikeExecutor.getCorePoolSize() != desiredHuman
          || humanLikeExecutor.getMaximumPoolSize() != desiredHuman) {
        resizePool(humanLikeExecutor, desiredHuman, "HUMAN");
        changed = true;
      }
      if (fastModeExecutor.getCorePoolSize() != desiredFast
          || fastModeExecutor.getMaximumPoolSize() != desiredFast) {
        resizePool(fastModeExecutor, desiredFast, "FAST");
        changed = true;
      }

      if (changed) {
        log.info(
            "Rebalanced pools -> human: {} threads, fast: {} threads (demand: human={} [queued:{}+in_process:{}+queue:{}], fast={} [queued:{}+in_process:{}+queue:{}])",
            desiredHuman, desiredFast, humanTotal, humanQueued, humanInProcess, humanQueueSize,
            fastTotal, fastQueued, fastInProcess, fastQueueSize);
      }
    } catch (Exception e) {
      log.debug("Rebalance failed: {}", e.getMessage());
    }
  }

  private void resizePool(ThreadPoolExecutor executor, int newSize, String label) {
    try {
      int oldCore = executor.getCorePoolSize();
      int oldMax = executor.getMaximumPoolSize();
      executor.setCorePoolSize(newSize);
      executor.setMaximumPoolSize(newSize);
      log.debug("Resized {} pool from core/max {}/{} -> {}/{}", label, oldCore, oldMax, newSize,
          newSize);
    } catch (IllegalArgumentException ignored) {
      log.debug("Resize {} pool failed: {}", label, ignored.getMessage());
    }
  }

  private void shutdownExecutorService(ExecutorService executor, String name) {
    if (executor != null) {
      executor.shutdown();
      try {
        if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
          executor.shutdownNow();
          log.warn("{} executor did not terminate gracefully", name);
        } else {
          log.info("{} executor terminated gracefully", name);
        }
      } catch (InterruptedException e) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();
        log.warn("{} executor shutdown interrupted", name);
      }
    }
  }
}
