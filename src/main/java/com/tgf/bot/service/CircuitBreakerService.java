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
 * 根据 {@link ContentModerationService} 的 DeepSeek 连续失败次数自动降级与恢复。
 *
 * <p>状态机：</p>
 * <ul>
 *   <li>GREEN（正常运行）— AI 审核正常</li>
 *   <li>YELLOW（降级模式）— 切换到规则引擎，自动恢复尝试</li>
 *   <li>RED（紧急模式）— 暂停所有自动化处罚，仅接收消息</li>
 * </ul>
 *
 * <p>降级阈值：DeepSeek 连续失败 ≥10 次触发 YELLOW。</p>
 * <p>恢复机制：每次成功调用减少 1 个失败计数，归零时自动恢复 GREEN。</p>
 *
 * <p>依赖：</p>
 * <ul>
 *   <li>无外部依赖（纯内存状态机，使用 {@link AtomicReference} 和 {@link AtomicInteger}）</li>
 * </ul>
 *
 * <p>被引用：</p>
 * <ul>
 *   <li>{@link GroupHandler} — 消息审核时检查熔断状态</li>
 *   <li>{@link ContentModerationService} — 调用成功/失败时更新计数</li>
 *   <li>{@link BotScheduler} — 定期尝试自动恢复</li>
 *   <li>{@link MiniAppController} — API 层系统状态查询</li>
 * </ul>
 *
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

        if (fails >= 10) {
            // CAS 保证状态转换的原子性，避免并发下重复降级
            currentState.compareAndSet(State.GREEN, State.YELLOW);
            if (currentState.get() == State.YELLOW && fails >= 10) {
                log.warn("🟡 System degraded to YELLOW mode - switching to rule engine");
            }
        }
    }

    public void recordDeepSeekSuccess() {
        int fails = deepseekFailures.updateAndGet(v -> Math.max(0, v - 1));
        if (fails == 0) {
            // CAS 保证只有 YELLOW 状态才能恢复到 GREEN
            currentState.compareAndSet(State.YELLOW, State.GREEN);
            if (currentState.get() == State.GREEN) {
                deepseekFailures.set(0);
                log.info("🟢 System recovered to GREEN from YELLOW");
            }
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
