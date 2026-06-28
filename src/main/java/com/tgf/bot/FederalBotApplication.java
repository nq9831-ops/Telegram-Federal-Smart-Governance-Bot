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
/**
 * FederalBotApplication — 应用启动入口。
 * 
 * TG 联邦智能治理机器人的 Spring Boot 启动类。
 * @since 1.0
 */
public class FederalBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(FederalBotApplication.class, args);
    }
}
