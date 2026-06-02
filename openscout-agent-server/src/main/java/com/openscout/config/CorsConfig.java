package com.openscout.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig {

    private final OpenScoutProperties properties;

    public CorsConfig(OpenScoutProperties properties) {
        this.properties = properties;
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                OpenScoutProperties.Cors cors = properties.getCors();
                registry.addMapping("/api/**")
                        .allowedOrigins(cors.getAllowedOrigins())
                        .allowedMethods("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*");
                registry.addMapping("/swagger-ui/**")
                        .allowedOrigins(cors.getAllowedOrigins())
                        .allowedMethods("GET", "OPTIONS");
                registry.addMapping("/v3/api-docs/**")
                        .allowedOrigins(cors.getAllowedOrigins())
                        .allowedMethods("GET", "OPTIONS");
            }
        };
    }
}
