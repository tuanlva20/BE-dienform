package com.dienform.common.service;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class CookieService {

  @Value("${app.cookie.domain:}")
  private String cookieDomain;

  @Value("${app.cookie.secure:true}")
  private boolean cookieSecure;

  @Value("${app.cookie.samesite:None}")
  private String cookieSameSite;

  public ResponseCookie buildAccessCookie(String token, long maxAgeSeconds) {
    ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from("access_token", token)
        .httpOnly(true).secure(cookieSecure).path("/").maxAge(Duration.ofSeconds(maxAgeSeconds));
    if (!cookieDomain.isBlank())
      b.domain(cookieDomain);
    if (cookieSameSite != null)
      b.sameSite(cookieSameSite);
    return b.build();
  }

  public ResponseCookie buildRefreshCookie(String token, long maxAgeSeconds) {
    ResponseCookie.ResponseCookieBuilder b =
        ResponseCookie.from("refresh_token", token).httpOnly(true).secure(cookieSecure)
            .path("/api/auth").maxAge(Duration.ofSeconds(maxAgeSeconds));
    if (!cookieDomain.isBlank())
      b.domain(cookieDomain);
    if (cookieSameSite != null)
      b.sameSite(cookieSameSite);
    return b.build();
  }

  public ResponseCookie clearCookie(String name) {
    ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(name, "").httpOnly(true)
        .secure(cookieSecure).path("/").maxAge(Duration.ZERO);
    if (!cookieDomain.isBlank())
      b.domain(cookieDomain);
    if (cookieSameSite != null)
      b.sameSite(cookieSameSite);
    return b.build();
  }

  public ResponseCookie clearCookie(String name, String path) {
    ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(name, "").httpOnly(true)
        .secure(cookieSecure).path(path).maxAge(Duration.ZERO);
    if (!cookieDomain.isBlank())
      b.domain(cookieDomain);
    if (cookieSameSite != null)
      b.sameSite(cookieSameSite);
    return b.build();
  }
}


