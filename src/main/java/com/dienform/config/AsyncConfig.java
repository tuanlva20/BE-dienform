package com.dienform.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import com.dienform.config.filter.RequestConcurrencyFilter;

@Configuration
@EnableAsync
public class AsyncConfig {

  @Value("${app.request-limiter.max-concurrent:2}")
  private int maxConcurrentRequests;

  @Bean(name = "taskExecutor")
  public Executor taskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(5);
    executor.setMaxPoolSize(10);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("Async-");
    executor.initialize();
    return executor;
  }

  @Bean
  public Semaphore requestSemaphore() {
    return new Semaphore(Math.max(1, maxConcurrentRequests));
  }

  @Bean
  public FilterRegistrationBean<RequestConcurrencyFilter> requestConcurrencyFilterRegistration(
      Semaphore requestSemaphore) {
    FilterRegistrationBean<RequestConcurrencyFilter> registration = new FilterRegistrationBean<>();
    registration.setFilter(new RequestConcurrencyFilter(requestSemaphore));
    registration.addUrlPatterns("/*");
    registration.setOrder(0);
    return registration;
  }
}
