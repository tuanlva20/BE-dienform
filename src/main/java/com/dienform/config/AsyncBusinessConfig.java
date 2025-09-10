package com.dienform.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import lombok.extern.slf4j.Slf4j;

/**
 * Configuration for business-specific async operations (form parsing, encoding, etc.) Separate from
 * Selenium thread pools to prevent blocking
 */
@Configuration
@Slf4j
public class AsyncBusinessConfig {

  @Value("${app.business.async.core-pool-size:0}")
  private int corePoolSize;

  @Value("${app.business.async.max-pool-size:6}")
  private int maxPoolSize;

  @Value("${app.business.async.queue-capacity:100}")
  private int queueCapacity;

  @Value("${app.business.async.thread-name-prefix:BizAsync-}")
  private String threadNamePrefix;

  /**
   * Business async executor for form parsing, encoding, and other business operations This is
   * completely separate from Selenium executors to prevent blocking
   */
  @Bean(name = "businessAsyncExecutor")
  public Executor businessAsyncExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(corePoolSize);
    executor.setMaxPoolSize(maxPoolSize);
    executor.setQueueCapacity(queueCapacity);
    executor.setThreadNamePrefix(threadNamePrefix);

    // Reject policy: caller runs to provide back-pressure
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

    // Allow core threads to timeout when idle to reduce sleeping threads
    executor.setAllowCoreThreadTimeOut(true);
    executor.setKeepAliveSeconds(60);

    // Wait for tasks to complete on shutdown
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(60);

    executor.initialize();

    log.info("Business async executor initialized: core={}, max={}, queue={}", corePoolSize,
        maxPoolSize, queueCapacity);

    return executor;
  }
}
