package com.dienform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.dienform.config.CampaignSchedulerConfig;

@SpringBootApplication
@EnableConfigurationProperties({CampaignSchedulerConfig.class})
public class DienformApplication {

	public static void main(String[] args) {
		SpringApplication.run(DienformApplication.class, args);
	}

}
