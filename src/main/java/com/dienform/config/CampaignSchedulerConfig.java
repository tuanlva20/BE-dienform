package com.dienform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import lombok.Data;

@Configuration
public class CampaignSchedulerConfig {

  @Data
  public static class CampaignSchedulerProperties {
    /**
     * Fixed rate for checking pending campaigns (in milliseconds) Default: 30000ms (30 seconds)
     */
    private long fixedRate = 30000;

    /**
     * Whether the campaign scheduler is enabled Default: true
     */
    private boolean enabled = true;
  }

  @Value("${campaign.scheduler.fixed-rate:30000}")
  private long fixedRate;

  @Value("${campaign.scheduler.enabled:true}")
  private boolean enabled;

  /**
   * Bean method to create the campaign scheduler configuration
   */
  @Bean(name = "campaignSchedulerProperties")
  public CampaignSchedulerProperties campaignSchedulerProperties() {
    CampaignSchedulerProperties properties = new CampaignSchedulerProperties();
    properties.setFixedRate(fixedRate);
    properties.setEnabled(enabled);
    return properties;
  }
}
