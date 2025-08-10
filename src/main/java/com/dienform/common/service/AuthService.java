package com.dienform.common.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.dienform.common.dto.auth.AuthResponse;
import com.dienform.common.dto.auth.AuthUserDto;
import com.dienform.common.dto.auth.TokenPair;
import com.dienform.common.entity.User;
import com.dienform.common.model.AuthProvider;
import com.dienform.common.repository.UserRepository;
import com.dienform.common.service.FacebookAuthService.FacebookUserInfo;
import com.dienform.common.service.GoogleAuthService.GoogleUserInfo;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

  private final UserRepository userRepository;
  private final JwtTokenProvider jwtTokenProvider;
  private final AvatarGeneratorService avatarGeneratorService;
  private final GoogleAuthService googleAuthService;
  private final FacebookAuthService facebookAuthService;

  public AuthResponse loginManual(String email, String password, String ip) {
    Optional<User> opt = userRepository.findByEmail(email);
    if (opt.isEmpty()) {
      return AuthResponse.builder().success(false).message("Email không tồn tại").build();
    }
    User user = opt.get();
    if (user.getPasswordHash() == null || !BCrypt.checkpw(password, user.getPasswordHash())) {
      return AuthResponse.builder().success(false).message("Mật khẩu không đúng").build();
    }
    user.setLastLoginIp(ip);
    userRepository.save(user);
    return buildAuthResponse(user);
  }

  public AuthResponse loginGoogle(String idToken, String ip,
      boolean enableIpVerificationOnRegister) {
    GoogleUserInfo info = googleAuthService.verifyIdToken(idToken);
    User user = userRepository.findByEmail(info.getEmail()).orElseGet(() -> {
      User u = User.builder().email(info.getEmail()).name(info.getName())
          .provider(AuthProvider.GOOGLE).providerUserId(info.getSub())
          .emailVerified(info.isEmailVerified())
          .createdIp(enableIpVerificationOnRegister ? ip : null).avatar(info.getPicture()).build();
      if (u.getAvatar() == null || u.getAvatar().isBlank()) {
        u.setAvatar(avatarGeneratorService
            .generateAvatar(u.getName() != null ? u.getName() : u.getEmail()));
      }
      if (u.getCreatedAt() == null) {
        u.setCreatedAt(LocalDateTime.now());
      }
      if (u.getUpdatedAt() == null) {
        u.setUpdatedAt(LocalDateTime.now());
      }
      return userRepository.save(u);
    });
    user.setLastLoginIp(ip);
    userRepository.save(user);
    return buildAuthResponse(user);
  }

  public AuthResponse loginFacebook(String accessToken, String ip,
      boolean enableIpVerificationOnRegister) {
    FacebookUserInfo info = facebookAuthService.verifyAccessTokenAndGetUser(accessToken);
    String email = info.getEmail();
    User user = (email != null ? userRepository.findByEmail(email).orElse(null) : null);
    if (user == null) {
      user =
          User.builder().email(email != null ? email : ("fb:" + info.getId() + "@facebook.local"))
              .name(info.getName()).provider(AuthProvider.FACEBOOK).providerUserId(info.getId())
              .emailVerified(true).createdIp(enableIpVerificationOnRegister ? ip : null)
              .avatar(info.getPicture()).build();
      if (user.getAvatar() == null || user.getAvatar().isBlank()) {
        user.setAvatar(avatarGeneratorService
            .generateAvatar(user.getName() != null ? user.getName() : user.getEmail()));
      }
      if (user.getCreatedAt() == null) {
        user.setCreatedAt(LocalDateTime.now());
      }
      if (user.getUpdatedAt() == null) {
        user.setUpdatedAt(LocalDateTime.now());
      }
      user = userRepository.save(user);
    }
    user.setLastLoginIp(ip);
    userRepository.save(user);
    return buildAuthResponse(user);
  }

  public AuthResponse refresh(String refreshToken) {
    DecodedJWT decoded = jwtTokenProvider.validateToken(refreshToken);
    if (!"refresh".equals(decoded.getClaim("type").asString())) {
      return AuthResponse.builder().success(false).message("Invalid refresh token").build();
    }
    String email = decoded.getSubject();
    User user =
        userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));
    return buildAuthResponse(user);
  }

  public AuthResponse register(String name, String email, String password, String confirmPassword,
      String ip) {
    if (email == null || !email.contains("@")) {
      throw new com.dienform.common.exception.BadRequestException("Email không hợp lệ");
    }
    if (password == null || password.length() < 6) {
      throw new com.dienform.common.exception.BadRequestException("Mật khẩu tối thiểu 6 ký tự");
    }
    if (confirmPassword == null || !confirmPassword.equals(password)) {
      throw new com.dienform.common.exception.BadRequestException("Xác nhận mật khẩu không khớp");
    }
    if (userRepository.findByEmail(email).isPresent()) {
      throw new com.dienform.common.exception.ConflictException("Email đã tồn tại");
    }

    String avatar = avatarGeneratorService.generateAvatar(name != null ? name : email);
    User user = User.builder().email(email).name(name)
        .passwordHash(BCrypt.hashpw(password, BCrypt.gensalt())).provider(AuthProvider.MANUAL)
        .emailVerified(false).createdIp(ip).avatar(avatar).build();
    if (user.getCreatedAt() == null) {
      user.setCreatedAt(LocalDateTime.now());
    }
    if (user.getUpdatedAt() == null) {
      user.setUpdatedAt(LocalDateTime.now());
    }
    userRepository.save(user);
    return buildAuthResponse(user);
  }

  private AuthResponse buildAuthResponse(User user) {
    Map<String, String> claims = new HashMap<>();
    claims.put("email", user.getEmail());
    claims.put("name", user.getName() != null ? user.getName() : "");
    String accessToken = jwtTokenProvider.generateAccessToken(user.getEmail(), claims);
    String refreshToken = jwtTokenProvider.generateRefreshToken(user.getEmail());

    AuthUserDto dto = AuthUserDto.builder().id(user.getId().toString()).email(user.getEmail())
        .name(user.getName()).avatar(user.getAvatar()).provider(user.getProvider().name())
        .role("user").build();

    return AuthResponse.builder().success(true).message("OK").data(dto)
        .tokens(TokenPair.builder().accessToken(accessToken).refreshToken(refreshToken).build())
        .build();
  }
}


