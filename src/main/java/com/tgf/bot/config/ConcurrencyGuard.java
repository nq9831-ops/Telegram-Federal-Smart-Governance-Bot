package com.tgf.bot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ConcurrencyGuard — 并发控制守卫。
 * 基于信号量和计数器实现全局并发控制与速率限制，
 * 防止 Virtual Thread 风暴打爆 DeepSeek API / ES / PG。
 * @since 1.0
 */
@Component
public class ConcurrencyGuard {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyGuard.class);

    /** DeepSeek API 并发调用上限 */
    private final Semaphore deepSeekSemaphore;

    /** 当前活跃的 ES 写入数监控 */
    private final AtomicInteger activeEsWrites = new AtomicInteger(0);
    private final int maxEsWriteConcurrency;

    public ConcurrencyGuard(
        @Value("${concurrency.deepseek-max:10}") int deepseekMax,
        @Value("${concurrency.es-write-max:20}") int esWriteMax
    ) {
        this.deepSeekSemaphore = new Semaphore(deepseekMax, true);
        this.maxEsWriteConcurrency = esWriteMax;
        log.info("ConcurrencyGuard initialized: deepseek={}, esWrite={}", deepseekMax, esWriteMax);
    }

    /**
     * 尝试获取 DeepSeek API 调用许可（阻塞等待最多 30s）
     * @return true 获取成功，false 超时
     */
    public boolean tryAcquireDeepSeek() {
        try {
            return deepSeekSemaphore.tryAcquire(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 释放 DeepSeek API 许可 */
    public void releaseDeepSeek() {
        deepSeekSemaphore.release();
    }

    /** 获取 DeepSeek 当前等待数 */
    public int getDeepSeekQueueLength() {
        return deepSeekSemaphore.getQueueLength();
    }

    /**
     * 尝试获取 ES 写入许可
     */
    public boolean tryAcquireEsWrite() {
        int cur = activeEsWrites.get();
        if (cur >= maxEsWriteConcurrency) {
            log.warn("ES write concurrency limit reached: {} >= {}", cur, maxEsWriteConcurrency);
            return false;
        }
        return activeEsWrites.incrementAndGet() <= maxEsWriteConcurrency;
    }

    /** 释放 ES 写入许可 */
    public void releaseEsWrite() {
        activeEsWrites.decrementAndGet();
    }
}
