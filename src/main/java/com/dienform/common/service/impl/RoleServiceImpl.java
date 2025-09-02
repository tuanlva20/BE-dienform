package com.dienform.common.service.impl;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import com.dienform.common.entity.Role;
import com.dienform.common.entity.User;
import com.dienform.common.entity.UserRole;
import com.dienform.common.repository.RoleRepository;
import com.dienform.common.repository.UserRepository;
import com.dienform.common.repository.UserRoleRepository;
import com.dienform.common.service.RoleService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoleServiceImpl implements RoleService {

  private static final String ROLE_ADMIN = "ADMIN";
  private static final String ROLE_USER = "USER";

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final UserRoleRepository userRoleRepository;

  @Override
  @Transactional
  public void grantAdminByEmail(String email) {
    User user = userRepository.findByEmail(email)
        .orElseThrow(() -> new RuntimeException("User not found: " + email));
    Role adminRole = roleRepository.findByName(ROLE_ADMIN)
        .orElseThrow(() -> new RuntimeException("Role not found: " + ROLE_ADMIN));

    boolean hasAdmin = userRoleRepository.findAllByUserIdFetchRole(user.getId()).stream()
        .anyMatch(ur -> ROLE_ADMIN.equalsIgnoreCase(ur.getRole().getName()));
    if (!hasAdmin) {
      UserRole ur = UserRole.builder().user(user).role(adminRole).build();
      userRoleRepository.save(ur);
      log.info("Granted ADMIN to {}", email);
    }
  }

  @Override
  @Transactional
  public void revokeAdminByEmail(String email) {
    User user = userRepository.findByEmail(email)
        .orElseThrow(() -> new RuntimeException("User not found: " + email));
    Role adminRole = roleRepository.findByName(ROLE_ADMIN)
        .orElseThrow(() -> new RuntimeException("Role not found: " + ROLE_ADMIN));

    userRoleRepository.deleteByUserIdAndRoleId(user.getId(), adminRole.getId());
    log.info("Revoked ADMIN from {}", email);
  }

  @Override
  public String getPrimaryRole(User user) {
    List<UserRole> roles = userRoleRepository.findAllByUserIdFetchRole(user.getId());
    Optional<UserRole> admin = roles.stream()
        .filter(ur -> ROLE_ADMIN.equalsIgnoreCase(ur.getRole().getName())).findFirst();
    return admin.isPresent() ? "admin" : "user";
  }

  @Override
  @Transactional
  public void ensureDefaultUserRole(User user) {
    List<UserRole> roles = userRoleRepository.findAllByUserIdFetchRole(user.getId());
    boolean hasUser =
        roles.stream().anyMatch(ur -> ROLE_USER.equalsIgnoreCase(ur.getRole().getName()));
    if (!hasUser) {
      Role userRole = roleRepository.findByName(ROLE_USER)
          .orElseThrow(() -> new RuntimeException("Role not found: " + ROLE_USER));
      userRoleRepository.save(UserRole.builder().user(user).role(userRole).build());
      log.info("Assigned default USER role to {}", user.getEmail());
    }
  }
}


