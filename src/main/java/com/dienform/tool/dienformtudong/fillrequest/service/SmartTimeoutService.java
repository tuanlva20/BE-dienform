package com.dienform.tool.dienformtudong.fillrequest.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

/**
 * Service để tính toán timeout thông minh dựa trên số lượng forms và loại request
 */
@Service
@Slf4j
public class SmartTimeoutService {

  @Value("${google.form.fast-timeout-seconds:3600}")
  private long fastTimeoutSeconds;

  @Value("${google.form.human-timeout-seconds:7200}")
  private long humanTimeoutSeconds;

  @Value("${google.form.heavy-load-threshold:50}")
  private int heavyLoadThreshold;

  @Value("${google.form.heavy-timeout-multiplier:2.0}")
  private double heavyTimeoutMultiplier;

  /**
   * Tính toán timeout tối ưu dựa trên số lượng forms và loại request
   */
  public long calculateOptimalTimeout(int totalForms, boolean isHumanLike) {
    long baseTimeout = isHumanLike ? humanTimeoutSeconds : fastTimeoutSeconds;

    log.info("Calculating timeout for {} forms (human-like: {})", totalForms, isHumanLike);
    log.info("Base timeout: {} seconds", baseTimeout);

    long timeoutSeconds = baseTimeout;

    // Apply heavy load multiplier if needed
    boolean heavyLoad = totalForms >= heavyLoadThreshold;
    if (heavyLoad) {
      timeoutSeconds = (long) Math.ceil(timeoutSeconds * Math.max(1.0, heavyTimeoutMultiplier));
      log.info("Heavy load detected ({} >= {}), applying multiplier: {}", totalForms,
          heavyLoadThreshold, heavyTimeoutMultiplier);
    }

    // Additional scaling for very large batches
    if (totalForms > 200) {
      // For very large batches, increase timeout proportionally
      double scaleFactor = Math.min(3.0, 1.0 + (totalForms - 200) / 200.0);
      timeoutSeconds = (long) Math.ceil(timeoutSeconds * scaleFactor);
      log.info("Large batch detected ({} forms), scaling timeout by factor: {}", totalForms,
          scaleFactor);
    }

    // Ensure minimum timeout for human-like requests
    if (isHumanLike && timeoutSeconds < 1800) { // Minimum 30 minutes for human-like
      timeoutSeconds = 1800;
      log.info("Enforcing minimum timeout for human-like: 1800 seconds");
    }

    // Ensure minimum timeout for fast-mode requests
    if (!isHumanLike && timeoutSeconds < 600) { // Minimum 10 minutes for fast-mode
      timeoutSeconds = 600;
      log.info("Enforcing minimum timeout for fast-mode: 600 seconds");
    }

    log.info("Final calculated timeout: {} seconds ({} minutes)", timeoutSeconds,
        timeoutSeconds / 60);

    return timeoutSeconds;
  }

  /**
   * Tính toán thời gian ước tính để hoàn thành tất cả forms
   */
  public long calculateEstimatedCompletionTime(int totalForms, boolean isHumanLike) {
    if (isHumanLike) {
      // Human-like: 2-15 phút mỗi form, trung bình 8.5 phút
      return (long) (totalForms * 8.5 * 60); // seconds
    } else {
      // Fast-mode: 1 phút mỗi form
      return totalForms * 60L; // seconds
    }
  }

  /**
   * Kiểm tra xem có cần batch processing không
   */
  public boolean needsBatchProcessing(int totalForms, boolean isHumanLike) {
    if (isHumanLike) {
      return totalForms > 50; // Human-like: batch nếu > 50 forms
    } else {
      return totalForms > 100; // Fast-mode: batch nếu > 100 forms
    }
  }

  /**
   * Tính toán batch size tối ưu
   */
  public int calculateOptimalBatchSize(int totalForms, boolean isHumanLike) {
    if (isHumanLike) {
      return Math.min(50, totalForms); // Human-like: max 50 forms/batch
    } else {
      return Math.min(100, totalForms); // Fast-mode: max 100 forms/batch
    }
  }

  /**
   * Tính toán số threads tối ưu cho fast mode
   */
  public int calculateOptimalFastThreads(int totalForms) {
    if (totalForms <= 50)
      return 1;
    if (totalForms <= 100)
      return 2;
    if (totalForms <= 200)
      return 3;
    if (totalForms <= 400)
      return 4;
    if (totalForms <= 800)
      return 6;
    return 8; // Maximum for very large batches
  }
}
