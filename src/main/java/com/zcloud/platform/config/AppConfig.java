package com.zcloud.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;

@Configuration
public class AppConfig {

    // Anti-pattern: creating beans here that could be auto-configured
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    // Anti-pattern: manually creating JdbcTemplate when JPA is already configured
    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
