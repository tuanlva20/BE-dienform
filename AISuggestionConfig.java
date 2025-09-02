package com.dienform.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Configuration for AI Suggestion service
 * Configures AI service client and related beans
 */
@Configuration
public class AISuggestionConfig {

  /**
   * Properties class for AI Suggestion configuration
   */
  public static class AISuggestionProperties {
    public static class GeminiProperties {
      private String apiKey;
      private String baseUrl = "https://generativelanguage.googleapis.com/v1beta/models";
      private String model = "gemini-2.0-flash";
      private Integer maxTokens = 800000;
      private Duration timeout = Duration.ofSeconds(30);

      // Getters and setters
      public String getApiKey() {
        return apiKey;
      }

      public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
      }

      public String getBaseUrl() {
        return baseUrl;
      }

      public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
      }

      public String getModel() {
        return model;
      }

      public void setModel(String model) {
        this.model = model;
      }

      public Integer getMaxTokens() {
        return maxTokens;
      }

      public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
      }

      public Duration getTimeout() {
        return timeout;
      }

      public void setTimeout(Duration timeout) {
        this.timeout = timeout;
      }
    }

    public static class RateLimitProperties {
      private Integer requestsPerMinute = 10;
      private Integer requestsPerHour = 100;
      private Integer requestsPerDay = 1000;

      // Getters and setters
      public Integer getRequestsPerMinute() {
        return requestsPerMinute;
      }

      public void setRequestsPerMinute(Integer requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
      }

      public Integer getRequestsPerHour() {
        return requestsPerHour;
      }

      public void setRequestsPerHour(Integer requestsPerHour) {
        this.requestsPerHour = requestsPerHour;
      }

      public Integer getRequestsPerDay() {
        return requestsPerDay;
      }

      public void setRequestsPerDay(Integer requestsPerDay) {
        this.requestsPerDay = requestsPerDay;
      }
    }

    public static class ValidationProperties {
      private Integer maxSampleCount = 1000;
      private Integer maxQuestionsPerForm = 50;
      private Integer minSampleCount = 1;
      private Integer maxInstructionLength = 500;

      // Getters and setters
      public Integer getMaxSampleCount() {
        return maxSampleCount;
      }

      public void setMaxSampleCount(Integer maxSampleCount) {
        this.maxSampleCount = maxSampleCount;
      }

      public Integer getMaxQuestionsPerForm() {
        return maxQuestionsPerForm;
      }

      public void setMaxQuestionsPerForm(Integer maxQuestionsPerForm) {
        this.maxQuestionsPerForm = maxQuestionsPerForm;
      }

      public Integer getMinSampleCount() {
        return minSampleCount;
      }

      public void setMinSampleCount(Integer minSampleCount) {
        this.minSampleCount = minSampleCount;
      }

      public Integer getMaxInstructionLength() {
        return maxInstructionLength;
      }

      public void setMaxInstructionLength(Integer maxInstructionLength) {
        this.maxInstructionLength = maxInstructionLength;
      }
    }

    private boolean enabled = true;
    private String provider = "gemini";

    private GeminiProperties gemini = new GeminiProperties();
    private RateLimitProperties rateLimit = new RateLimitProperties();

    private ValidationProperties validation = new ValidationProperties();

    // Getters and setters
    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getProvider() {
      return provider;
    }

    public void setProvider(String provider) {
      this.provider = provider;
    }

    public GeminiProperties getGemini() {
      return gemini;
    }

    public void setGemini(GeminiProperties gemini) {
      this.gemini = gemini;
    }

    public RateLimitProperties getRateLimit() {
      return rateLimit;
    }

    public void setRateLimit(RateLimitProperties rateLimit) {
      this.rateLimit = rateLimit;
    }

    public ValidationProperties getValidation() {
      return validation;
    }

    public void setValidation(ValidationProperties validation) {
      this.validation = validation;
    }
  }

  @Bean
  @ConfigurationProperties(prefix = "ai.suggestion")
  public AISuggestionProperties aiSuggestionProperties() {
    return new AISuggestionProperties();
  }

  @Bean
  public RestTemplate aiRestTemplate() {
    RestTemplate restTemplate = new RestTemplate();

    // Configure timeout
    restTemplate.getInterceptors().add((request, body, execution) -> {
      request.getHeaders().add("User-Agent", "DienForm-AI-Client/1.0");
      return execution.execute(request, body);
    });

    return restTemplate;
  }
}
