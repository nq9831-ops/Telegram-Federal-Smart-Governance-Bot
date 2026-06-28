package com.tgf.bot.config;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.GetUpdates;
import com.tgf.bot.handler.BotHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * BotPollingRunner — Telegram Long Polling 兜底实现。
 * <p>
 * 当未配置 Webhook 地址时，通过 TelegramBot 内置的 UpdatesListener 接收消息。
 * SDK 内部自动管理偏移量和超时，自动重连。
 * Webhook 模式和 LongPolling 模式互斥，通过 bot.polling.enabled 切换。
 * </p>
 * @since 1.0
 */
@Component
@ConditionalOnProperty(name = "bot.polling.enabled", havingValue = "true", matchIfMissing = false)
public class BotPollingRunner {

    private static final Logger log = LoggerFactory.getLogger(BotPollingRunner.class);

    private final TelegramBot bot;
    private final List<BotHandler> handlers;

    /** getUpdates 长轮询超时（秒），必须为正数 */
    @Value("${bot.polling.timeout-sec:30}")
    private int pollTimeoutSec;

    /** 单次 getUpdates 最大更新数 */
    @Value("${bot.polling.limit:100}")
    private int pollLimit;

    private volatile boolean running = false;

    public BotPollingRunner(TelegramBot bot, List<BotHandler> handlers) {
        this.bot = bot;
        this.handlers = handlers;
    }

    @PostConstruct
    public void start() {
        log.info("Bot LongPolling enabled, timeout={}s limit={}", pollTimeoutSec, pollLimit);

        GetUpdates request = new GetUpdates()
                .limit(pollLimit)
                .timeout(pollTimeoutSec);

        running = true;
        bot.setUpdatesListener(updates -> {
            try {
                for (Update update : updates) {
                    processUpdate(update);
                }
            } catch (Exception e) {
                log.error("LongPolling callback error: {}", e.getMessage(), e);
            }
            return UpdatesListener.CONFIRMED_UPDATES_ALL;
        }, request);

        log.info("Bot LongPolling started");
    }

    private void processUpdate(Update update) {
        try {
            for (BotHandler handler : handlers) {
                if (handler.canHandle(update)) {
                    handler.handle(update, bot);
                    return;
                }
            }
        } catch (Exception e) {
            log.error("Error processing update {}: {}", update.updateId(), e.getMessage(), e);
        }
    }

    @PreDestroy
    public void stop() {
        log.info("Stopping Bot LongPolling...");
        running = false;
        bot.removeGetUpdatesListener();
    }
}
