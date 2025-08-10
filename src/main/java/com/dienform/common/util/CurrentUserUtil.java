package com.dienform.common.util;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.dienform.common.entity.User;
import com.dienform.common.repository.UserRepository;
import com.dienform.common.service.JwtTokenProvider;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CurrentUserUtil {

  private final JwtTokenProvider jwtTokenProvider;
  private final UserRepository userRepository;

  public Optional<UUID> getCurrentUserIdIfPresent() {
    return getCurrentUserIfPresent().map(User::getId);
  }

  public Optional<User> getCurrentUserIfPresent() {
    HttpServletRequest request = getCurrentHttpRequest();
    if (request == null) {
      return Optional.empty();
    }
    String token = extractTokenFromRequest(request);
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    try {
      DecodedJWT decoded = jwtTokenProvider.validateToken(token);
      String email = decoded.getSubject();
      return userRepository.findByEmail(email);
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  public UUID requireCurrentUserId() {
    return getCurrentUserIdIfPresent().orElseThrow(() -> new RuntimeException("Unauthorized"));
  }

  private HttpServletRequest getCurrentHttpRequest() {
    RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
    if (attrs instanceof ServletRequestAttributes servletAttributes) {
      return servletAttributes.getRequest();
    }
    return null;
  }

  private String extractTokenFromRequest(HttpServletRequest request) {
    String authorization = request.getHeader("Authorization");
    if (authorization != null && authorization.startsWith("Bearer ")) {
      return authorization.substring("Bearer ".length());
    }
    Cookie[] cookies = request.getCookies();
    if (cookies != null) {
      return Arrays.stream(cookies).filter(c -> "access_token".equals(c.getName()))
          .map(Cookie::getValue).findFirst().orElse(null);
    }
    return null;
  }
}


