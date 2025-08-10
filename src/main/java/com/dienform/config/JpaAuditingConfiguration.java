package com.dienform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Configuration class to enable JPA auditing functionality for automatic population of createdAt
 * and updatedAt fields
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfiguration {
}
