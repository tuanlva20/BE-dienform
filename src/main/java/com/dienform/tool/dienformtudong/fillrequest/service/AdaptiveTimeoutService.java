package com.dienform.tool.dienformtudong.fillrequest.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

/**
 * Service để tính toán timeout adaptive dựa trên vị trí trong queue và thread pool
 */
@Service
@Slf4j
public class AdaptiveTimeoutService {

  @Value("${google.form.adaptive-timeout.base-form-timeout:300}")
  private long baseFormTimeout; // 5 phút

  @Value("${google.form.adaptive-timeout.max-form-timeout:1800}")
  private long maxFormTimeout; // 30 phút tối đa

  @Value("${google.form.adaptive-timeout.queue-wait-multiplier:60}")
  private long queueWaitMultiplier; // 1 phút/queue position

  @Autowired
  private ThreadPoolManager threadPoolManager;

  /**
   * Tính toán timeout adaptive cho form dựa trên queue position
   */
  public long calculateFormTimeout(int totalForms, int currentQueueSize, int threadPoolSize) {
    // Base timeout cho việc xử lý 1 form
    long baseTimeout = baseFormTimeout;

    // Tính toán thời gian chờ trong queue
    int estimatedQueuePosition = Math.max(0, currentQueueSize / Math.max(1, threadPoolSize));
    long queueWaitTime = estimatedQueuePosition * queueWaitMultiplier;

    // Timeout tổng = thời gian chờ + thời gian xử lý
    long totalTimeout = queueWaitTime + baseTimeout;

    // Giới hạn tối đa
    long finalTimeout = Math.min(totalTimeout, maxFormTimeout);

    log.info(
        "Adaptive timeout calculation: totalForms={}, queueSize={}, threadPool={}, "
            + "queuePosition={}, queueWait={}s, baseTimeout={}s, finalTimeout={}s ({}min)",
        totalForms, currentQueueSize, threadPoolSize, estimatedQueuePosition, queueWaitTime,
        baseTimeout, finalTimeout, finalTimeout / 60);

    return finalTimeout;
  }

  /**
   * Tính toán timeout cho batch processing
   */
  public long calculateBatchTimeout(int batchSize, int totalBatches) {
    // Timeout cho batch = số forms trong batch * base timeout + buffer
    long batchTimeout = batchSize * baseFormTimeout + 300; // +5 phút buffer

    // Giới hạn tối đa 60 phút cho batch
    long maxBatchTimeout = 3600; // 60 phút

    long finalTimeout = Math.min(batchTimeout, maxBatchTimeout);

    log.info(
        "Batch timeout calculation: batchSize={}, totalBatches={}, " + "batchTimeout={}s ({}min)",
        batchSize, totalBatches, finalTimeout, finalTimeout / 60);

    return finalTimeout;
  }

  /**
   * Tính toán batch size tối ưu dựa trên queue capacity
   */
  public int calculateOptimalBatchSize(int totalForms, int queueCapacity) {
    // Batch size = min(queueCapacity/2, totalForms/4, 50)
    int optimalBatchSize = Math.min(Math.min(queueCapacity / 2, totalForms / 4), 50);

    // Đảm bảo batch size tối thiểu là 10
    optimalBatchSize = Math.max(optimalBatchSize, 10);

    log.info(
        "Optimal batch size calculation: totalForms={}, queueCapacity={}, " + "optimalBatchSize={}",
        totalForms, queueCapacity, optimalBatchSize);

    return optimalBatchSize;
  }

  /**
   * Kiểm tra xem có cần batch processing không
   */
  public boolean needsBatchProcessing(int totalForms, int queueCapacity) {
    // Cần batch nếu totalForms > queueCapacity/2
    boolean needsBatch = totalForms > queueCapacity / 2;

    log.info("Batch processing check: totalForms={}, queueCapacity={}, needsBatch={}", totalForms,
        queueCapacity, needsBatch);

    return needsBatch;
  }
}
