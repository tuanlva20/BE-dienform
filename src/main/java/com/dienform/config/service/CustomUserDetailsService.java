package com.dienform.config.service;

import java.util.Collections;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import com.dienform.common.entity.User;
import com.dienform.common.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomUserDetailsService implements UserDetailsService {

  private final UserRepository userRepository;

  @Override
  public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
    log.debug("Loading user by email: {}", email);

    User user = userRepository.findByEmail(email).orElseThrow(() -> {
      log.warn("User not found with email: {}", email);
      return new UsernameNotFoundException("User not found with email: " + email);
    });

    log.debug("Found user: {} with email: {}", user.getName(), user.getEmail());

    return org.springframework.security.core.userdetails.User.builder().username(user.getEmail())
        .password("") // JWT authentication doesn't use password
        .authorities(Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")))
        .accountExpired(false).accountLocked(false).credentialsExpired(false).disabled(false)
        .build();
  }
}
