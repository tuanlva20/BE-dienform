package com.dienform.common.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.dienform.common.dto.auth.AuthResponse;
import com.dienform.common.dto.auth.LoginRequest;
import com.dienform.common.dto.auth.RefreshTokenRequest;
import com.dienform.common.service.AuthService;
import com.dienform.common.service.CookieService;
import com.dienform.common.service.JwtTokenProvider;
import com.dienform.common.util.IpUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

  public static class GoogleLoginRequestBody {
    public String idToken;

    public String getIdToken() {
      return idToken;
    }

    public void setIdToken(String idToken) {
      this.idToken = idToken;
    }
  }

  public static class FacebookLoginRequestBody {
    public String accessToken;

    public String getAccessToken() {
      return accessToken;
    }

    public void setAccessToken(String accessToken) {
      this.accessToken = accessToken;
    }
  }

  // Use DTO with validation annotations
  public static class RegisterRequestBody {
  }

  private final AuthService authService;
  private final CookieService cookieService;

  private final JwtTokenProvider jwtTokenProvider;

  @Value("${app.security.enable-ip-on-register:false}")
  private boolean enableIpOnRegister;

  @PostMapping("/login")
  public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request,
      HttpServletRequest httpRequest) {
    String ip = IpUtils.extractClientIp(httpRequest);
    AuthResponse res = authService.loginManual(request.getEmail(), request.getPassword(), ip);
    return withAuthCookies(res);
  }

  @PostMapping("/google")
  public ResponseEntity<AuthResponse> google(@RequestBody GoogleLoginRequestBody request,
      HttpServletRequest httpRequest) {
    String ip = IpUtils.extractClientIp(httpRequest);
    AuthResponse res = authService.loginGoogle(request.idToken, ip, enableIpOnRegister);
    return withAuthCookies(res);
  }

  @PostMapping("/facebook")
  public ResponseEntity<AuthResponse> facebook(@RequestBody FacebookLoginRequestBody request,
      HttpServletRequest httpRequest) {
    String ip = IpUtils.extractClientIp(httpRequest);
    AuthResponse res = authService.loginFacebook(request.accessToken, ip, enableIpOnRegister);
    return withAuthCookies(res);
  }

  @PostMapping("/refresh")
  public ResponseEntity<AuthResponse> refresh(
      @RequestBody(required = false) RefreshTokenRequest request,
      @CookieValue(name = "refresh_token", required = false) String refreshCookie) {
    String refreshToken = request != null ? request.getRefreshToken() : null;
    if (refreshToken == null || refreshToken.isBlank())
      refreshToken = refreshCookie;
    AuthResponse res = authService.refresh(refreshToken);
    return withAuthCookies(res);
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout() {
    // Match original cookie paths to ensure deletion works across browsers
    ResponseCookie clearAccess = cookieService.clearCookie("access_token", "/");
    ResponseCookie clearRefresh = cookieService.clearCookie("refresh_token", "/api/auth");
    return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, clearAccess.toString())
        .header(HttpHeaders.SET_COOKIE, clearRefresh.toString()).build();
  }

  @PostMapping("/register")
  public ResponseEntity<AuthResponse> register(
      @Valid @RequestBody com.dienform.common.dto.auth.RegisterRequest body,
      HttpServletRequest httpRequest) {
    String ip = IpUtils.extractClientIp(httpRequest);
    AuthResponse res = authService.register(body.getName(), body.getEmail(), body.getPassword(),
        body.getConfirmPassword(), ip);
    return withAuthCookies(res);
  }

  private ResponseEntity<AuthResponse> withAuthCookies(AuthResponse response) {
    long accessTtlSec = Math.max(1, jwtTokenProvider.getAccessExpirationMs() / 1000);
    long refreshTtlSec = jwtTokenProvider.getRefreshExpirationSec();
    String accessToken =
        response.getTokens() != null ? response.getTokens().getAccessToken() : null;
    String refreshToken =
        response.getTokens() != null ? response.getTokens().getRefreshToken() : null;

    ResponseCookie access = cookieService.buildAccessCookie(accessToken, accessTtlSec);
    ResponseCookie refresh = cookieService.buildRefreshCookie(refreshToken, refreshTtlSec);

    // Do not expose tokens in response body; they are set as HttpOnly cookies
    response.setTokens(null);
    return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, access.toString())
        .header(HttpHeaders.SET_COOKIE, refresh.toString()).body(response);
  }
}


