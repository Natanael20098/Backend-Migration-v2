package com.zcloud.platform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

// Anti-pattern: unnecessary config class that just logs things
@Configuration
public class DatabaseConfig {

    @Value("${spring.datasource.url:not-set}")
    private String datasourceUrl;

    // Anti-pattern: logging credentials at startup
    @PostConstruct
    public void logDatabaseConfig() {
        System.out.println("=== Database Configuration ===");
        System.out.println("URL: " + datasourceUrl);
        System.out.println("==============================");
    }
}
