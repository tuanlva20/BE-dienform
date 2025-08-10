package com.dienform.common.util;

import jakarta.servlet.http.HttpServletRequest;

public class IpUtils {
  public static String extractClientIp(HttpServletRequest request) {
    if (request == null)
      return null;
    String ip = request.getHeader("X-Forwarded-For");
    if (ip != null && !ip.isBlank()) {
      int comma = ip.indexOf(',');
      if (comma > -1) {
        return ip.substring(0, comma).trim();
      }
      return ip.trim();
    }
    ip = request.getHeader("X-Real-IP");
    if (ip != null && !ip.isBlank())
      return ip.trim();
    return request.getRemoteAddr();
  }
}


