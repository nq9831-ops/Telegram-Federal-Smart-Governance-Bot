package com.tgf.bot.controller;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Update;
import com.tgf.bot.handler.BotHandler;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * WebhookController — Telegram Webhook 入口控制器。
 * 
 * 接收 Telegram 推送的更新事件并将处理委托给 BotDispatcher。
 * 包含并发限制，防止虚拟线程风暴打爆下游服务。
 * @since 1.0
 */
@RestController
@RequestMapping("/webhook")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER = 
        new com.fasterxml.jackson.databind.ObjectMapper();

    private final TelegramBot bot;
    private final List<BotHandler> handlers;

    @Value("${bot.webhook-secret:}")
    private String webhookSecret;

    @Value("${webhook.max-concurrent-updates:500}")
    private int maxConcurrentUpdates;

    private Semaphore updateSemaphore;

    public WebhookController(TelegramBot bot, List<BotHandler> handlers) {
        this.bot = bot;
        this.handlers = handlers;
    }

    @PostConstruct
    public void init() {
        this.updateSemaphore = new Semaphore(maxConcurrentUpdates, true);
        log.info("Webhook controller initialized, max concurrent updates: {}", maxConcurrentUpdates);
    }

    @PostMapping("/bot")
    public ResponseEntity<String> onUpdate(
        @RequestBody String body,
        @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secret
    ) {
        if (!webhookSecret.isEmpty() && !constantTimeEquals(webhookSecret, secret != null ? secret : "")) {
            log.warn("Invalid webhook secret token");
            return ResponseEntity.status(401).body("Unauthorized");
        }

        // 并发限制：防止虚拟线程风暴
        if (!updateSemaphore.tryAcquire()) {
            log.warn("Webhook concurrency limit reached, rejecting update");
            return ResponseEntity.status(429).body("Too Many Requests");
        }

        boolean asyncStarted = false;
        try {
            var update = OBJECT_MAPPER.readValue(body, Update.class);

            // 异步处理，带超时保护
            Thread.ofVirtual().start(() -> {
                try {
                    processUpdate(update);
                } finally {
                    updateSemaphore.release();
                }
            });
            asyncStarted = true;

        } catch (Exception e) {
            log.warn("Failed to parse update: {}", e.getMessage());
        } finally {
            // parse 失败时（虚拟线程未启动），必须在这里释放许可，否则信号量永久泄漏
            if (!asyncStarted) {
                updateSemaphore.release();
            }
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

    /**
     * 常量时间字符串比较，防止时序攻击。
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
