package com.dienform.common.entity;

import com.dienform.common.model.AuthProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User extends AuditEntity {

  @NotBlank
  @Email
  @Column(name = "email", unique = true, nullable = false)
  private String email;

  @Column(name = "name")
  private String name;

  @Column(name = "password_hash")
  private String passwordHash;

  @Column(name = "avatar", columnDefinition = "TEXT")
  private String avatar;

  @Enumerated(EnumType.STRING)
  @Column(name = "provider", nullable = false)
  private AuthProvider provider;

  @Column(name = "provider_user_id")
  private String providerUserId;

  @Column(name = "email_verified")
  private Boolean emailVerified;

  @Column(name = "created_ip")
  private String createdIp;

  @Column(name = "last_login_ip")
  private String lastLoginIp;

  // createdAt and updatedAt are inherited from AuditEntity
}


