package com.tgf.bot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * TG 联邦智能治理机器人 - 应用入口。Spring Boot 3.2.5 + Java 21。
 * @since 1.0
 */
@SpringBootApplication
@EnableCaching
@EnableScheduling
public class FederalBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(FederalBotApplication.class, args);
    }
}
