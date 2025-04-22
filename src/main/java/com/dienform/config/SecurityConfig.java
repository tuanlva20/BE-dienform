// package com.dienform.config;

// import org.springframework.context.annotation.Bean;
// import org.springframework.context.annotation.Configuration;
// import org.springframework.security.config.annotation.web.builders.HttpSecurity;
// import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
// import org.springframework.security.config.http.SessionCreationPolicy;
// import org.springframework.security.web.SecurityFilterChain;
// import org.springframework.web.cors.CorsConfiguration;
// import org.springframework.web.cors.CorsConfigurationSource;
// import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

// import java.util.Arrays;

// @Configuration
// @EnableWebSecurity
// public class SecurityConfig {

// @Bean
// public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
// http
// .csrf().disable()
// .cors().and()
// .authorizeRequests(authorizeRequests ->
// authorizeRequests
// .antMatchers("/api/v1/public/**").permitAll()
// .antMatchers("/actuator/**").permitAll()
// .antMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
// // For now we're permitting all authenticated paths for simplicity
// // In a production environment, you would add proper authentication
// .antMatchers("/api/v1/**").permitAll()
// .anyRequest().authenticated()
// )
// .sessionManagement()
// .sessionCreationPolicy(SessionCreationPolicy.STATELESS);

// return http.build();
// }

// @Bean
// public CorsConfigurationSource corsConfigurationSource() {
// CorsConfiguration configuration = new CorsConfiguration();
// configuration.setAllowedOrigins(Arrays.asList("*"));
// configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE",
// "OPTIONS"));
// configuration.setAllowedHeaders(Arrays.asList("authorization", "content-type", "x-auth-token"));
// configuration.setExposedHeaders(Arrays.asList("x-auth-token"));
// UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
// source.registerCorsConfiguration("/**", configuration);
// return source;
// }
// }
