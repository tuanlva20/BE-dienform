package com.dienform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import lombok.Getter;
import lombok.Setter;

/**
 * Configuration for AI Suggestion Queue System
 */
@Configuration
public class AISuggestionQueueConfig {

    /**
     * AI Suggestion Queue Properties
     */
    @Getter
    @Setter
    public static class AISuggestionQueueProperties {
        private int maxSize = 50;
        private int maxConcurrent = 3;
        private long processingInterval = 5000; // 5 seconds
        private int maxRetries = 3;
        private long retryDelay = 10000;
    }

    /**
     * Bean for AI Suggestion Queue Properties
     */
    @Bean
    @ConfigurationProperties(prefix = "ai.suggestion.queue")
    public AISuggestionQueueProperties aiSuggestionQueueProperties() {
        return new AISuggestionQueueProperties();
    }

    /**
     * Bean for processing interval (used in @Scheduled annotation)
     */
    @Bean(name = "aiSuggestionQueueService")
    public long processingInterval() {
        return 5000L; // Default 5 seconds
    }
}
