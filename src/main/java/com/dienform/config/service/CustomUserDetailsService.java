package com.dienform.config.service;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import com.dienform.common.entity.User;
import com.dienform.common.repository.UserRepository;
import com.dienform.common.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomUserDetailsService implements UserDetailsService {

  private final UserRepository userRepository;
  private final UserRoleRepository userRoleRepository;

  @Override
  public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
    log.debug("Loading user by email: {}", email);

    User user = userRepository.findByEmail(email).orElseThrow(() -> {
      log.warn("User not found with email: {}", email);
      return new UsernameNotFoundException("User not found with email: " + email);
    });

    log.debug("Found user: {} with email: {}", user.getName(), user.getEmail());

    List<SimpleGrantedAuthority> authorities =
        userRoleRepository.findAllByUserIdFetchRole(user.getId()).stream()
            .map(ur -> new SimpleGrantedAuthority("ROLE_" + ur.getRole().getName().toUpperCase()))
            .collect(Collectors.toList());
    if (authorities.isEmpty()) {
      authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    return org.springframework.security.core.userdetails.User.builder().username(user.getEmail())
        .password("").authorities(authorities).accountExpired(false).accountLocked(false)
        .credentialsExpired(false).disabled(false).build();
  }
}
