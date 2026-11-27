package com.cricket.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CricketPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(CricketPlatformApplication.class, args);
    }
}
