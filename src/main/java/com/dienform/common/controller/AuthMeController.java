package com.dienform.common.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.dienform.common.dto.auth.AuthResponse;
import com.dienform.common.dto.auth.AuthUserDto;
import com.dienform.common.entity.User;
import com.dienform.common.repository.UserRepository;
import com.dienform.common.service.JwtTokenProvider;
import com.dienform.common.service.RoleService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthMeController {

  private final JwtTokenProvider jwtTokenProvider;
  private final UserRepository userRepository;
  private final RoleService roleService;

  @GetMapping("/me")
  public ResponseEntity<AuthResponse> me(
      @RequestHeader(name = "Authorization", required = false) String authorization,
      @CookieValue(name = "access_token", required = false) String accessCookie) {
    try {
      String token = null;
      if (authorization != null && authorization.startsWith("Bearer ")) {
        token = authorization.substring("Bearer ".length());
      } else if (accessCookie != null && !accessCookie.isBlank()) {
        token = accessCookie;
      }
      if (token == null) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(AuthResponse.builder().success(false).message("Unauthorized").build());
      }
      DecodedJWT decoded = jwtTokenProvider.validateToken(token);
      String email = decoded.getSubject();
      User user = userRepository.findByEmail(email)
          .orElseThrow(() -> new RuntimeException("User not found"));
      String role = roleService.getPrimaryRole(user);
      AuthUserDto dto = AuthUserDto.builder().id(user.getId().toString()).email(user.getEmail())
          .name(user.getName()).avatar(user.getAvatar()).provider(user.getProvider().name())
          .role(role).build();
      long expEpochSeconds = decoded.getExpiresAt().toInstant().getEpochSecond();
      return ResponseEntity.ok(AuthResponse.builder().success(true).message("OK").data(dto)
          .exp(expEpochSeconds).build());
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(AuthResponse.builder().success(false).message("Unauthorized").build());
    }
  }
}


