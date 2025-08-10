package com.dienform.common.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthUserDto {
  private String id;
  private String email;
  private String name;
  private String avatar;
  private String provider;
  private String role;
}


