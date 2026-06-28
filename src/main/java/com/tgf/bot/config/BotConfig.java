package com.tgf.bot.config;

import com.pengrad.telegrambot.TelegramBot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * BotConfig — Telegram Bot 客户端配置（生产环境）。
 * 当 bot.token 配置了真实值（非 dummy:token 前缀）时，创建真实 TelegramBot 实例。
 * @since 1.0
 */
@Configuration
@ConditionalOnExpression("!'${bot.token:dummy:token}'.startsWith('dummy:')")
public class BotConfig {

    private static final Logger log = LoggerFactory.getLogger(BotConfig.class);

    @Bean
    public TelegramBot telegramBot(@Value("${bot.token}") String token) {
        String masked = token.length() > 8 ? "..." + token.substring(token.length() - 6) : "***";
        log.info("TelegramBot initialized, token suffix: {}", masked);
        return new TelegramBot(token);
    }
}
