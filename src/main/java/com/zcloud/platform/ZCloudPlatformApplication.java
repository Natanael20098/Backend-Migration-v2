package com.zcloud.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ZCloudPlatformApplication {

    // Anti-pattern: main class also has some initialization logic and mutable static state
    public static String APP_START_TIME = null;
    public static boolean IS_INITIALIZED = false;

    public static void main(String[] args) {
        APP_START_TIME = java.time.Instant.now().toString();
        System.out.println("=== HomeLend Pro Platform Starting ===");
        System.out.println("WARNING: This is a development build. Do not use in production.");
        SpringApplication.run(ZCloudPlatformApplication.class, args);
        IS_INITIALIZED = true;
        System.out.println("=== HomeLend Pro Platform Started at " + APP_START_TIME + " ===");
    }
}
