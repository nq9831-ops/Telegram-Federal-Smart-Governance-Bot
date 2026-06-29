package com.tgf.bot.config;

import com.tgf.bot.handler.BotHandler;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * BotHandlerConfig — Bot Handler 注册配置。
 * 启动时自动注入所有 BotHandler 实现并打印已注册处理器列表。
 * @since 1.0
 */
@Configuration
public class BotHandlerConfig {

    private static final Logger log = LoggerFactory.getLogger(BotHandlerConfig.class);

    private final List<BotHandler> handlers;

    public BotHandlerConfig(List<BotHandler> handlers) {
        this.handlers = handlers;
    }

    @PostConstruct
    public void init() {
        log.info("Bot handlers registered: {}", handlers.size());

        if (!handlers.isEmpty()) {
            log.info("Handler list:");
            for (BotHandler handler : handlers) {
                log.info("  - {}", handler.getClass().getSimpleName());
            }
        }
    }
}
