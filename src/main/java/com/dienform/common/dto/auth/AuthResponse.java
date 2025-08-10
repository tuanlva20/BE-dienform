package com.dienform.common.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthResponse {
  private boolean success;
  private String message;
  private AuthUserDto data;
  private TokenPair tokens;
  // access token expiry epoch seconds (for FE to schedule refresh -60s)
  private Long exp;
}


