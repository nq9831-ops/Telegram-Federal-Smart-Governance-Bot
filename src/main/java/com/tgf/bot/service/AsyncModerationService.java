package com.tgf.bot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pengrad.telegrambot.TelegramBot;
import com.tgf.bot.config.ConcurrencyGuard;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AsyncModerationService — AI 审核异步队列服务。
 * <p>使用 Redis List 作为消息队列，将 AI 审核从 Webhook 主流程解耦。
 * 失败消息进入死信队列，不丢失。</p>
 * <p>队列结构：</p>
 * <ul>
 *   <li>moderation:queue — 待审核队列（LPUSH/BRPOP）</li>
 *   <li>moderation:dlq — 死信队列（3次失败后进入）</li>
 *   <li>moderation:retry:{msgId} — 重试计数器</li>
 * </ul>
 * @since 1.0
 */
@Service
public class AsyncModerationService {

    private static final Logger log = LoggerFactory.getLogger(AsyncModerationService.class);

    private static final String QUEUE_KEY = "moderation:queue";
    private static final String DLQ_KEY = "moderation:dlq";
    private static final String RETRY_KEY_PREFIX = "moderation:retry:";
    private static final int MAX_RETRIES = 3;

    private final StringRedisTemplate redisTemplate;
    private final ContentModerationService moderationService;
    private final CreditEngine creditEngine;
    private final TelegramBot bot;
    private final ConcurrencyGuard guard;
    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService consumerPool;

    @Value("${moderation.async.consumers:4}")
    private int consumerThreads;

    public AsyncModerationService(StringRedisTemplate redisTemplate,
                                   ContentModerationService moderationService,
                                   CreditEngine creditEngine,
                                   TelegramBot bot,
                                   ConcurrencyGuard guard) {
        this.redisTemplate = redisTemplate;
        this.moderationService = moderationService;
        this.creditEngine = creditEngine;
        this.bot = bot;
        this.guard = guard;
    }

    @PostConstruct
    public void start() {
        if (consumerThreads <= 0) {
            log.info("Async moderation disabled (consumers=0)");
            return;
        }
        running.set(true);
        consumerPool = Executors.newFixedThreadPool(consumerThreads, Thread.ofVirtual().factory());
        for (int i = 0; i < consumerThreads; i++) {
            consumerPool.submit(this::consumeLoop);
        }
        log.info("Async moderation queue started with {} consumers", consumerThreads);
    }

    /**
     * 提交消息到异步审核队列。
     */
    public void submit(long userId, long chatId, int messageId, String text) {
        try {
            Map<String, Object> task = Map.of(
                    "userId", userId,
                    "chatId", chatId,
                    "messageId", messageId,
                    "text", text != null ? text : "",
                    "enqueuedAt", System.currentTimeMillis()
            );
            String json = mapper.writeValueAsString(task);
            redisTemplate.opsForList().leftPush(QUEUE_KEY, json);
        } catch (Exception e) {
            log.error("Failed to enqueue moderation task for user={}: {}", userId, e.getMessage());
            // 队列不可用时降级为同步审核
            try {
                var result = moderationService.classifyMessage(text);
                if (result != null && result.isViolation() && result.isAutoExecutable()) {
                    String action = result.isScam() ? "scam" :
                                    result.isPolitical() ? "political" : "other";
                    int penalty = result.isDeathPenalty() ? -100 : -10;
                    creditEngine.applyByDeepSeek(userId, penalty, action,
                        result.getBriefReason() != null ? result.getBriefReason() : "auto-moderated");
                }
            } catch (Exception ex) {
                log.error("Sync moderation fallback failed: {}", ex.getMessage());
            }
        }
    }

    private void consumeLoop() {
        while (running.get()) {
            try {
                // BRPOP 阻塞等待消息
                String json = redisTemplate.opsForList()
                        .rightPop(QUEUE_KEY, Duration.ofSeconds(5));
                if (json == null) continue;

                processTask(json);
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("Moderation consumer error: {}", e.getMessage());
                }
            }
        }
    }

    private void processTask(String json) {
        Map task;
        try {
            task = mapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse moderation task: {}", e.getMessage());
            return; // 无法解析，丢入 DLQ
        }

        long userId = ((Number) task.get("userId")).longValue();
        long chatId = ((Number) task.get("chatId")).longValue();
        int messageId = ((Number) task.get("messageId")).intValue();
        String text = (String) task.getOrDefault("text", "");
        String retryKey = RETRY_KEY_PREFIX + userId + ":" + chatId + ":" + messageId;

        try {
            if (!guard.tryAcquireDeepSeek()) {
                log.warn("DeepSeek rate limit reached, requeueing user={}", userId);
                redisTemplate.opsForList().leftPush(QUEUE_KEY, json);
                Thread.sleep(1000);
                return;
            }
            try {
                var result = moderationService.classifyMessage(text);
                if (result != null && result.isViolation() && result.isAutoExecutable()) {
                    String action = result.isScam() ? "scam" :
                                    result.isPolitical() ? "political" :
                                    result.isPorn() ? "porn" :
                                    result.isGambling() ? "gambling" : "ad";
                    int penalty = result.isDeathPenalty() ? -100 :
                                  result.isScam() ? -20 :
                                  result.isPolitical() ? -20 :
                                  result.isPorn() ? -15 :
                                  result.isGambling() ? -10 : -5;
                    creditEngine.applyByDeepSeek(userId, penalty, action,
                        "[" + action + "] " + result.getBriefReason());
                }
                // 成功处理，清理重试计数
                redisTemplate.delete(retryKey);
            } finally {
                guard.releaseDeepSeek();
            }
        } catch (Exception e) {
            log.warn("Moderation failed for user={}: {}", userId, e.getMessage());
            // 重试计数
            Long retries = redisTemplate.opsForValue().increment(retryKey);
            redisTemplate.expire(retryKey, 1, TimeUnit.HOURS);

            if (retries != null && retries < MAX_RETRIES) {
                // 退回队列，延迟重试
                redisTemplate.opsForList().leftPush(QUEUE_KEY, json);
                log.info("Requeued task, retry {}/{} for user={}", retries, MAX_RETRIES, userId);
            } else {
                // 超过重试次数，进入死信队列
                redisTemplate.opsForList().leftPush(DLQ_KEY, json);
                log.error("Task moved to DLQ after {} retries, user={}", MAX_RETRIES, userId);
            }
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (consumerPool != null) {
            consumerPool.shutdownNow();
        }
        log.info("Async moderation service stopped");
    }

    /**
     * 获取待处理队列长度。
     */
    public long getQueueSize() {
        Long size = redisTemplate.opsForList().size(QUEUE_KEY);
        return size != null ? size : 0;
    }

    /**
     * 获取死信队列长度。
     */
    public long getDlqSize() {
        Long size = redisTemplate.opsForList().size(DLQ_KEY);
        return size != null ? size : 0;
    }
}
