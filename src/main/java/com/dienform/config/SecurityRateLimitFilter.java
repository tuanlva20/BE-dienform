package com.dienform.config;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@Component
@Order(2) // Run after SecurityFilter
@Slf4j
public class SecurityRateLimitFilter implements Filter {

  private static final int MAX_REQUESTS_PER_MINUTE = 100;
  private static final long RESET_INTERVAL = 60000; // 1 minute

  private final ConcurrentHashMap<String, AtomicInteger> requestCounts = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Long> lastResetTimes = new ConcurrentHashMap<>();

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {

    HttpServletRequest httpRequest = (HttpServletRequest) request;
    HttpServletResponse httpResponse = (HttpServletResponse) response;

    String clientIP = getClientIP(httpRequest);
    long currentTime = System.currentTimeMillis();

    // Reset counter if needed
    Long lastReset = lastResetTimes.get(clientIP);
    if (lastReset == null || (currentTime - lastReset) > RESET_INTERVAL) {
      requestCounts.put(clientIP, new AtomicInteger(0));
      lastResetTimes.put(clientIP, currentTime);
    }

    // Check rate limit
    AtomicInteger count = requestCounts.get(clientIP);
    if (count != null && count.incrementAndGet() > MAX_REQUESTS_PER_MINUTE) {
      log.warn("RATE LIMIT EXCEEDED: IP {} made {} requests in 1 minute", clientIP, count.get());

      httpResponse.setStatus(429); // 429 Too Many Requests
      httpResponse.getWriter().write("Too many requests");
      return;
    }

    chain.doFilter(request, response);
  }

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {
    log.info("SecurityRateLimitFilter initialized");
  }

  @Override
  public void destroy() {
    log.info("SecurityRateLimitFilter destroyed");
  }

  private String getClientIP(HttpServletRequest request) {
    String xForwardedFor = request.getHeader("X-Forwarded-For");
    if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
      return xForwardedFor.split(",")[0].trim();
    }

    String xRealIP = request.getHeader("X-Real-IP");
    if (xRealIP != null && !xRealIP.isEmpty()) {
      return xRealIP;
    }

    return request.getRemoteAddr();
  }
}
