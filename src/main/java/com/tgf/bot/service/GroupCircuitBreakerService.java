package com.tgf.bot.service;

import com.tgf.bot.model.GroupEntity;
import com.tgf.bot.repository.GroupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * GroupCircuitBreakerService — 群组级熔断服务。
 * 
 * 三级熔断层次：L1 群组级（QPS/违规率）、L2 用户级（跨群违规次数）、
 * L3 全局级（系统熔断器）。支持 DROP/SLOW/MUTE 等熔断动作。
 * @since 1.0
 */

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


public class GroupCircuitBreakerService {

    private static final Logger log = LoggerFactory.getLogger(GroupCircuitBreakerService.class);

    public enum GroupAction { DROP, SLOW, MUTE, PASS, RESTRICT_USER }

    /* ────────────── 群组滑动窗口（内存）─────────────── */
    // 每个群的 60 秒窗口：总消息数、违规数
    private final ConcurrentHashMap<Long, SlidingWindow> groupWindows = new ConcurrentHashMap<>();

    // 用户级熔断：userId -> 解除时间戳（ms）
    private final ConcurrentHashMap<Long, Long> userCircuitBreaks = new ConcurrentHashMap<>();

    // 配置参数（可在 application.properties 覆盖）
    private static final long WINDOW_MS = 60_000;
    private static final int CLEANUP_INTERVAL_MS = 60_000;

    // 群组熔断阈值
    private final int maxQpsPerGroup;        // 每秒 QPS 上限
    private final int maxViolationRate;      // 60 秒窗口内违规比例（万分比）
    private final long groupMuteSeconds;     // 群组熔断时长（秒）
    private final int slowModeQps;           // 超过此 QPS 开启慢速模式
    private final int slowModeSec;           // 慢速模式消息间隔秒数
    private final int userViolationLimit;    // 单个用户多群组违规次数上限

    // 上次清理时间
    private final AtomicLong lastCleanup = new AtomicLong(System.currentTimeMillis());

    private final GroupRepository groupRepo;
    private final CircuitBreakerService globalBreaker;

    public GroupCircuitBreakerService(
        GroupRepository groupRepo,
        CircuitBreakerService globalBreaker
    ) {
        this.groupRepo = groupRepo;
        this.globalBreaker = globalBreaker;

        // 可调参数（后续可改为 @Value 注入）
        this.maxQpsPerGroup = 5;           // 每秒 >5 条消息触发防护
        this.maxViolationRate = 3000;      // 60s 内违规 ≥ 30% 触发熔断
        this.groupMuteSeconds = 300;       // 熔断持续 5 分钟
        this.slowModeQps = 3;              // 每秒 >3 条开启慢速
        this.slowModeSec = 5;              // 慢速时间隔 5 秒
        this.userViolationLimit = 5;       // 用户跨群违规 ≥ 5 次

        log.info("GroupCircuitBreakerService initialized: qps={} violationRate={}% slowQps={} userLimit={}",
            maxQpsPerGroup, maxViolationRate / 100, slowModeQps, userViolationLimit);
    }

    /**
     * 每次消息到达时调用 — 返回对该群/用户应执行的动作。
     * 在 GroupHandler.handleMessage() 入口处同步调用。
     */
    public GroupAction check(Long chatId, Long userId) {
        periodicCleanup();

        // L3 全局熔断 — YELLOW/RED 先走规则引擎，不由我们决定
        // 但我们仍检查群组级 QPS

        // L1 群组级检查
        SlidingWindow window = groupWindows.computeIfAbsent(chatId, k -> new SlidingWindow());
        window.record();

        // 检查数据库中的持续熔断状态
        GroupEntity group = groupRepo.findById(chatId).orElse(null);
        if (group != null) {
            if (group.isCircuitBroken()) {
                // 检查是否已到恢复时间
                if (group.getCircuitRecoverAt() != null
                    && LocalDateTime.now().isAfter(group.getCircuitRecoverAt())) {
                    recoverGroup(chatId);
                } else {
                    log.warn("Group {} circuit broken until {}: {}",
                        chatId, group.getCircuitRecoverAt(), group.getCircuitReason());
                    return GroupAction.DROP;
                }
            }
        }

        // 实时 QPS 检查
        double qps = window.qps();
        int violationCount = window.getViolationCount();
        int totalCount = window.getTotalCount();

        if (qps > maxQpsPerGroup) {
            // QPS 爆炸 → 立即开启慢速模式（强制全员慢速）
            log.warn("Group {} QPS={} exceeds {}: enabling slow mode", chatId, qps, maxQpsPerGroup);
            if (group != null) {
                group.setSlowModeSec(slowModeSec);
                groupRepo.save(group);
            }
            // 查询 QPS 继续爆炸 → 熔断
            if (qps > maxQpsPerGroup * 2) {
                breakGroup(chatId, String.format("QPS暴增: %.1f/s (阈值 %d/s)", qps, maxQpsPerGroup));
                return GroupAction.DROP;
            }
            return GroupAction.SLOW;
        }

        // 违规率爆表 → 熔断群组
        if (totalCount >= 10 && violationCount * 10000 / (long) totalCount > maxViolationRate) {
            breakGroup(chatId, String.format("违规率超标: %d/%d (阈值 %d%%)",
                violationCount, totalCount, maxViolationRate / 100));
            return GroupAction.DROP;
        }

        // L2 用户级检查
        Long userBannedUntil = userCircuitBreaks.get(userId);
        if (userBannedUntil != null) {
            if (System.currentTimeMillis() < userBannedUntil) {
                log.info("User {} circuit broken until {}", userId, userBannedUntil);
                return GroupAction.RESTRICT_USER;
            } else {
                userCircuitBreaks.remove(userId);
            }
        }

        // 如果群开启了慢速模式但 QPS 已降到阈值下 → 自动恢复
        if (group != null && group.getSlowModeSec() > 0 && qps < slowModeQps * 0.5) {
            group.setSlowModeSec(0);
            groupRepo.save(group);
        }

        return GroupAction.PASS;
    }

    /**
     * 用户违规计数条目，含计数和最后更新时间戳，支持过期清理。
     */
    static class UserViolationEntry {
        final AtomicInteger count = new AtomicInteger(0);
        volatile long lastUpdated = System.currentTimeMillis();

        int incrementAndGet() {
            lastUpdated = System.currentTimeMillis();
            return count.incrementAndGet();
        }

        boolean isExpired(long cutoff) {
            return lastUpdated < cutoff;
        }
    }

    // 用户违规计数（跨群组聚合，30 分钟自然过期）
    private final ConcurrentHashMap<Long, UserViolationEntry> userViolationCounts = new ConcurrentHashMap<>();

    /**
     * 记录一次违规（群组窗口 + 用户熔断计数器）。
     * 由处罚引擎（PenaltyEngine）在执行处罚后调用。
     */
    public void recordViolation(Long chatId, Long userId) {
        // 记入群窗口
        SlidingWindow window = groupWindows.get(chatId);
        if (window != null) {
            window.recordViolation();
        }

        // 用户级：跨群违规计数 — 存入临时 map，超过 30 分钟无活动自动过期
        UserViolationEntry entry = userViolationCounts.computeIfAbsent(userId, k -> new UserViolationEntry());
        int count = entry.incrementAndGet();
        if (count >= userViolationLimit) {
            // 熔断该用户 10 分钟
            long bannedUntil = System.currentTimeMillis() + 600_000;
            userCircuitBreaks.put(userId, bannedUntil);
            log.warn("User {} circuit broken for 10min: {} violations across groups", userId, count);
            userViolationCounts.remove(userId); // 重置计数
        }
    }

    /** 判断是否在慢速模式 */
    public boolean isInSlowMode(Long chatId, GroupEntity group) {
        if (group != null && group.getSlowModeSec() > 0) return true;
        return false;
    }

    /** 熔断整个群组 */
    private void breakGroup(Long chatId, String reason) {
        GroupEntity group = groupRepo.findById(chatId).orElse(null);
        if (group == null) return;

        group.setCircuitBroken(true);
        group.setCircuitReason(reason);
        group.setCircuitBrokeAt(LocalDateTime.now());
        group.setCircuitRecoverAt(LocalDateTime.now().plusSeconds(groupMuteSeconds));
        groupRepo.save(group);

        log.warn("🔴 Group {} circuit BREAK: {} (recover at {})",
            chatId, reason, group.getCircuitRecoverAt());
    }

    /** 恢复群组 */
    private void recoverGroup(Long chatId) {
        GroupEntity group = groupRepo.findById(chatId).orElse(null);
        if (group == null) return;
        group.setCircuitBroken(false);
        group.setCircuitReason(null);
        group.setCircuitBrokeAt(null);
        group.setCircuitRecoverAt(null);
        group.setSlowModeSec(0);
        groupRepo.save(group);
        log.info("🟢 Group {} circuit recovered", chatId);

        // 清除内存窗口
        groupWindows.remove(chatId);
    }

    /** 手动恢复（管理员命令） */
    public void manualRecover(Long chatId) {
        recoverGroup(chatId);
    }

    /** 获取群组当前状态摘要 */
    public Map<String, Object> getGroupStatus(Long chatId) {
        SlidingWindow w = groupWindows.get(chatId);
        GroupEntity g = groupRepo.findById(chatId).orElse(null);
        return Map.of(
            "qps", w != null ? w.qps() : 0.0,
            "total_in_window", w != null ? w.getTotalCount() : 0,
            "violations_in_window", w != null ? w.getViolationCount() : 0,
            "circuit_broken", g != null && g.isCircuitBroken(),
            "circuit_reason", g != null ? g.getCircuitReason() : "",
            "slow_mode_sec", g != null ? g.getSlowModeSec() : 0
        );
    }

    /** 窗口清理 */
    private void periodicCleanup() {
        long now = System.currentTimeMillis();
        long last = lastCleanup.get();
        if (now - last < CLEANUP_INTERVAL_MS) return;
        if (!lastCleanup.compareAndSet(last, now)) return;

        groupWindows.entrySet().removeIf(e -> e.getValue().isExpired());

        // 清理过期用户违规计数（>= 30 分钟没有新的违规）及已过期的熔断记录
        long cutoff = now - 1_800_000;
        userViolationCounts.entrySet().removeIf(e -> e.getValue().isExpired(cutoff));
        userCircuitBreaks.entrySet().removeIf(e -> System.currentTimeMillis() >= e.getValue());
    }

    /* ────────────── 滑动窗口实现 ─────────────── */
    static class SlidingWindow {
        private static final int BUCKETS = 12; // 12 × 5s = 60s
        private static final long BUCKET_MS = 5_000;

        private final long[] timestamps = new long[BUCKETS];
        private final int[] counts = new int[BUCKETS];
        private final int[] violationCounts = new int[BUCKETS];
        private long lastTick = System.currentTimeMillis();

        SlidingWindow() {
            long now = System.currentTimeMillis();
            for (int i = 0; i < BUCKETS; i++) {
                timestamps[i] = now - (BUCKETS - 1 - i) * BUCKET_MS;
            }
        }

        synchronized void record() {
            tick();
            counts[BUCKETS - 1]++;
        }

        synchronized void recordViolation() {
            tick();
            violationCounts[BUCKETS - 1]++;
        }

        synchronized int getTotalCount() {
            tick();
            int sum = 0;
            for (int c : counts) sum += c;
            return sum;
        }

        synchronized int getViolationCount() {
            tick();
            int sum = 0;
            for (int c : violationCounts) sum += c;
            return sum;
        }

        synchronized double qps() {
            tick();
            int sum = 0;
            for (int c : counts) sum += c;
            return sum / (WINDOW_MS / 1000.0);
        }

        synchronized boolean isExpired() {
            // 如果窗口全空且最后活动超过 2 个窗口周期
            long now = System.currentTimeMillis();
            if (now - lastTick > BUCKET_MS * BUCKETS * 2) {
                int sum = 0;
                for (int c : counts) sum += c;
                return sum == 0;
            }
            return false;
        }

        private void tick() {
            long now = System.currentTimeMillis();
            long oldest = now - WINDOW_MS;
            for (int i = 0; i < BUCKETS; i++) {
                if (timestamps[i] < oldest) {
                    timestamps[i] = now - (BUCKETS - 1 - i) * BUCKET_MS;
                    counts[i] = 0;
                    violationCounts[i] = 0;
                }
            }
            lastTick = now;
        }
    }
}
