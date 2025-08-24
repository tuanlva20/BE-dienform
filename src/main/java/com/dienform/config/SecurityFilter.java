package com.dienform.config;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
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
@Order(1) // High priority filter
@Slf4j
public class SecurityFilter implements Filter {

  // Danh sách các pattern nhạy cảm cần chặn cho Java Spring Boot project
  private static final List<String> SENSITIVE_PATTERNS = Arrays.asList(
      // Environment files (most critical)
      ".env", "config.env", "env",

      // Configuration files - chặn chính xác các file application
      "config.js", "config.yml", "config.yaml", "config.json", "application.yml",
      "application.yaml", "application.properties", "application-", "application_", // Chặn tất cả
                                                                                    // file bắt đầu
                                                                                    // bằng
                                                                                    // application-

      // Java specific files
      ".class", ".jar", ".war", ".ear",

      // Backup files
      ".bak", ".backup", ".old", ".tmp", ".temp",

      // Database files
      ".sql", ".db", ".sqlite", ".sqlite3",

      // Log files
      ".log", "logs/",

      // System files
      ".htaccess", ".htpasswd", "web.config", "robots.txt",

      // IDE files
      ".idea/", ".vscode/", ".git/", ".svn/",

      // Docker files
      "Dockerfile", "docker-compose.yml", "docker-compose.yaml",

      // Maven/Gradle files
      "pom.xml", "build.gradle", "gradle.properties", "settings.gradle",

      // Other sensitive patterns
      "target/", "build/", "node_modules/", "vendor/");

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {

    HttpServletRequest httpRequest = (HttpServletRequest) request;
    HttpServletResponse httpResponse = (HttpServletResponse) response;

    String requestURI = httpRequest.getRequestURI().toLowerCase();
    String clientIP = getClientIP(httpRequest);

    // Check for sensitive patterns
    if (isSensitiveRequest(requestURI)) {
      log.warn("BLOCKED SENSITIVE REQUEST: {} from IP: {} - User-Agent: {}", requestURI, clientIP,
          httpRequest.getHeader("User-Agent"));

      // Return 404 to avoid revealing file existence
      httpResponse.setStatus(HttpServletResponse.SC_NOT_FOUND);
      httpResponse.getWriter().write("Resource not found");
      return;
    }

    // Continue with normal processing
    chain.doFilter(request, response);
  }

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {
    log.info("SecurityFilter initialized - blocking sensitive file access");
  }

  @Override
  public void destroy() {
    log.info("SecurityFilter destroyed");
  }

  private boolean isSensitiveRequest(String requestURI) {
    return SENSITIVE_PATTERNS.stream()
        .anyMatch(pattern -> requestURI.contains(pattern.toLowerCase()));
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
