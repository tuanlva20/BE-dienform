package com.dienform.config.filter;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

public class RequestConcurrencyFilter implements Filter {

  private final Semaphore semaphore;

  public RequestConcurrencyFilter(Semaphore semaphore) {
    this.semaphore = semaphore;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    boolean acquired = false;
    try {
      semaphore.acquire();
      acquired = true;
      chain.doFilter(request, response);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (response instanceof HttpServletResponse resp) {
        resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      }
    } finally {
      if (acquired) {
        semaphore.release();
      }
    }
  }
}


