package com.tgf.bot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CircuitBreakerService — 系统级熔断器服务。
 * 
 * 三级状态（GREEN / YELLOW / RED）控制系统行为，
 * 根据 DeepSeek 连续失败次数自动降级与恢复。
 * @since 1.0
 */
@Service
public class CircuitBreakerService {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerService.class);

    public enum State {
        GREEN("正常运行"),
        YELLOW("降级模式-规则引擎"),
        RED("紧急模式-仅接收");

        public final String label;
        State(String label) { this.label = label; }
    }

    private final AtomicReference<State> currentState = new AtomicReference<>(State.GREEN);

    // DeepSeek 连续失败计数器
    private final AtomicInteger deepseekFailures = new AtomicInteger(0);

    // YELLOW 触发后自动恢复的尝试计数器
    private final AtomicInteger recoveryAttempts = new AtomicInteger(0);

    public State getCurrentState() { return currentState.get(); }

    public boolean isGreen() { return currentState.get() == State.GREEN; }
    public boolean isYellow() { return currentState.get() == State.YELLOW; }
    public boolean isRed() { return currentState.get() == State.RED; }

    /* 尝试自动恢复（YELLOW 模式下的自动恢复检查） */
    public void attemptRecovery() {
        if (currentState.get() == State.YELLOW) {
            deepseekFailures.set(Math.max(0, deepseekFailures.get() - 3));
            int attempts = recoveryAttempts.incrementAndGet();
            if (deepseekFailures.get() == 0) {
                recoverToGreen();
            }
            log.info("Recovery attempt {}: failures now {}", attempts, deepseekFailures.get());
        }
    }

    public void recordDeepSeekFailure() {
        int fails = deepseekFailures.incrementAndGet();
        log.warn("DeepSeek failure #{}: current state={}", fails, currentState.get());

        if (fails >= 10 && currentState.get() == State.GREEN) {
            degradeToYellow();
        }
    }

    public void recordDeepSeekSuccess() {
        deepseekFailures.set(Math.max(0, deepseekFailures.get() - 1));
        if (deepseekFailures.get() == 0 && currentState.get() == State.YELLOW) {
            recoverToGreen();
        }
    }

    public synchronized void degradeToYellow() {
        if (currentState.get() == State.GREEN) {
            currentState.set(State.YELLOW);
            log.warn("🟡 System degraded to YELLOW mode - switching to rule engine");
        }
    }

    public synchronized void degradeToRed() {
        currentState.set(State.RED);
        log.error("🔴 System degraded to RED mode - pausing all automated actions");
    }

    public synchronized void recoverToGreen() {
        State old = currentState.get();
        currentState.set(State.GREEN);
        deepseekFailures.set(0);
        log.info("🟢 System recovered to GREEN from {}", old);
    }

    public synchronized void setState(State state) {
        currentState.set(state);
        log.warn("Manual state change to: {}", state);
    }
}
