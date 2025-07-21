package com.dienform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import lombok.Data;

@ConfigurationProperties(prefix = "campaign.scheduler")
@Data
public class CampaignSchedulerConfig {

  /**
   * Fixed rate for checking pending campaigns (in milliseconds) Default: 30000ms (30 seconds)
   */
  private long fixedRate = 30000;

  /**
   * Whether the campaign scheduler is enabled Default: true
   */
  private boolean enabled = true;
}
