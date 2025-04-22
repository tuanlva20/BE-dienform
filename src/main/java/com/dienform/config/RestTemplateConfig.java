package com.dienform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Configuration class for RestTemplate and related beans
 */
@Configuration
public class RestTemplateConfig {

    /**
     * Creates the RestTemplate bean used for HTTP requests
     * 
     * @return RestTemplate instance
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
