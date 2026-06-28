package com.tgf.bot.config;

import com.pengrad.telegrambot.TelegramBot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fallback TelegramBot bean —— 仅在无真实 Bot Token 时生效
 */
@Configuration
public class TestBotConfig {

    @Bean
    @ConditionalOnMissingBean(TelegramBot.class)
    public TelegramBot telegramBot() {
        return new TelegramBot("test:local_dev_token") {};
    }
}
