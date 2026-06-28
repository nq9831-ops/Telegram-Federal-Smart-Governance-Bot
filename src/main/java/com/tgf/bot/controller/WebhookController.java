package com.tgf.bot.controller;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Update;
import com.tgf.bot.handler.BotHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;

/**
 * Webhook 入口控制器 - Telegram 消息接收端点。
 */
@RestController
@RequestMapping("/webhook")
/**
 * WebhookController — Telegram Webhook 入口控制器。
 * 
 * 接收 Telegram 推送的更新事件并将处理委托给 BotDispatcher。
 * @since 1.0
 */
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private static final Duration UPDATE_TIMEOUT = Duration.ofSeconds(30);

    private final TelegramBot bot;
    private final List<BotHandler> handlers;

    @Value("${bot.webhook-secret:}")
    private String webhookSecret;

    public WebhookController(TelegramBot bot, List<BotHandler> handlers) {
        this.bot = bot;
        this.handlers = handlers;
    }

    @PostMapping("/bot")
    public ResponseEntity<String> onUpdate(
        @RequestBody String body,
        @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secret
    ) {
        if (!webhookSecret.isEmpty() && !webhookSecret.equals(secret)) {
            log.warn("Invalid webhook secret token");
            return ResponseEntity.status(401).body("Unauthorized");
        }

        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var update = mapper.readValue(body, Update.class);

            // 异步处理，带超时保护
            Thread.ofVirtual().start(() -> processUpdate(update));

        } catch (Exception e) {
            log.warn("Failed to parse update: {}", e.getMessage());
        }

        return ResponseEntity.ok("ok");
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
            log.error("Error processing update: {}", e.getMessage(), e);
        }
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("ok");
    }
}
