package com.dienform.tool.dienformtudong.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Data;

@Data
@Configuration
@ConfigurationProperties(prefix = "sepay")
public class SEPAYPaymentConfig {

  private String secretKey;
  private String qrBaseUrl;
  private String apiUrl;
  private String webhookUrl;
  private int orderExpirationMinutes = 15;
  private int webhookRetryMinutes = 5;
  private int maxRetryAttempts = 3;
}
