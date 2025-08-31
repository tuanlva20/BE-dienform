package com.dienform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

  // @Value("${app.frontend.url}")
  // private String frontendUrl;

  // @Override
  // public void addCorsMappings(CorsRegistry registry) {
  //   registry.addMapping("/**").allowedOrigins(frontendUrl)
  //       .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS").allowedHeaders("*")
  //       .allowCredentials(true).maxAge(3600);
  // }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    // Handle static resources properly
    registry.addResourceHandler("/static/**").addResourceLocations("classpath:/static/")
        .setCachePeriod(3600); // Cache for 1 hour

    // Block all sensitive file patterns for Java Spring Boot project
    registry
        .addResourceHandler("/*.env", "/config.env", "/config.js", "/application.yml",
            "/application.yaml", "/application.properties", "/application-*.yml",
            "/application-*.yaml", "/application-*.properties", "/*.sql", "/*.log", "/*.bak",
            "/*.class", "/*.jar", "/*.war", "/*.ear", "/Dockerfile", "/docker-compose.yml",
            "/docker-compose.yaml", "/pom.xml", "/build.gradle", "/.htaccess", "/web.config",
            "/.idea/**", "/.vscode/**", "/.git/**", "/target/**")
        .addResourceLocations("classpath:/blocked/").setCachePeriod(0);
  }
}
