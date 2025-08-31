package com.dienform.config;

import java.util.Arrays;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import com.dienform.config.filter.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {
  private final JwtAuthenticationFilter jwtAuthenticationFilter;

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(authorizeRequests -> authorizeRequests
            // Public endpoints (no auth required)
            .requestMatchers("/api/v1/public/**").permitAll().requestMatchers("/actuator/**")
            .permitAll().requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()

            // Webhook endpoints (API Key auth only)
            .requestMatchers("/api/payments/sepay/webhook").permitAll()
            .requestMatchers("/api/payments/*/webhook").permitAll()

            // Auth endpoints (no auth required for login/register)
            .requestMatchers("/api/auth/login").permitAll().requestMatchers("/api/auth/google")
            .permitAll().requestMatchers("/api/auth/facebook").permitAll()
            .requestMatchers("/api/auth/register").permitAll().requestMatchers("/api/auth/refresh")
            .permitAll()

            // All other API endpoints require authentication
            .requestMatchers("/api/**").authenticated().anyRequest().authenticated())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

    return http.build();
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(Arrays.asList("http://localhost:3000", "https://khaosat.tech"));

    configuration
        .setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

    configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type",
        "X-Requested-With", "Accept", "Origin", "Access-Control-Request-Method",
        "Access-Control-Request-Headers", "Cache-Control", "Pragma", "X-Auth-Token", "Cookie",
        "Set-Cookie", "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform", "Sec-Fetch-Dest",
        "Sec-Fetch-Mode", "Sec-Fetch-Site", "User-Agent"));

    configuration.setExposedHeaders(Arrays.asList("X-Auth-Token", "Set-Cookie",
        "Access-Control-Allow-Origin", "Access-Control-Allow-Credentials"));
    configuration.setAllowCredentials(true);
    configuration.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
