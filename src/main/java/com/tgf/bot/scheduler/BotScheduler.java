package com.tgf.bot.scheduler;

import com.tgf.bot.lock.DistributedLockService;
import com.tgf.bot.model.*;
import com.tgf.bot.repository.UserRepository;
import com.tgf.bot.service.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BotScheduler — 定时任务调度器。
 * <p>
 * 负责定期执行系统维护任务，包括每日信用分自动恢复、
 * SLA 超时工单升级、冷启动期检查、违规率分析、排行榜结算等。
 * <p>
 * 所有定时任务使用 PostgreSQL advisory lock 实现分布式互斥，
 * 防止多实例部署时重复执行。
 * @since 1.0
 */
@Component
public class BotScheduler {

    private static final Logger log = LoggerFactory.getLogger(BotScheduler.class);

    private static final long LOCK_TIMEOUT_MS = 100;

    @PersistenceContext
    private EntityManager em;

    private final UserRepository userRepo;
    private final CreditEngine creditEngine;
    private final ContentModerationService moderationService;
    private final CircuitBreakerService circuitBreaker;
    private final GroupCircuitBreakerService groupBreaker;
    private final TemplateEngine templateEngine;
    private final DistributedLockService distributedLock;

    @Value("${credit.daily-auto-increment:1}")
    private int dailyAutoIncrement;

    @Value("${credit.max-score:100}")
    private int maxScore;

    @Value("${ranking.top-1-pct-reward:5}")
    private int top1Reward;

    @Value("${ranking.top-2-5-pct-reward:3}")
    private int top25Reward;

    @Value("${ranking.top-6-15-pct-reward:1}")
    private int top615Reward;

    @Value("${ranking.bottom-5-pct-penalty:-5}")
    private int bottom5Penalty;

    @Value("${ranking.bottom-6-15-pct-penalty:-2}")
    private int bottom615Penalty;

    public BotScheduler(UserRepository userRepo, CreditEngine creditEngine,
                        ContentModerationService moderationService,
                        CircuitBreakerService circuitBreaker,
                        GroupCircuitBreakerService groupBreaker,
                        TemplateEngine templateEngine,
                        DistributedLockService distributedLock) {
        this.userRepo = userRepo;
        this.creditEngine = creditEngine;
        this.moderationService = moderationService;
        this.circuitBreaker = circuitBreaker;
        this.groupBreaker = groupBreaker;
        this.templateEngine = templateEngine;
        this.distributedLock = distributedLock;
    }

    /** 每日 04:00 — 链接巡检：标记失效群组/机器人/代理 */
    @Scheduled(cron = "0 0 4 * * ?", zone = "Asia/Shanghai")
    @Transactional
    public void linkHealthCheck() {
        if (!distributedLock.tryLock("scheduler:linkHealthCheck", LOCK_TIMEOUT_MS)) {
            log.debug("linkHealthCheck: another instance holds the lock, skipping");
            return;
        }
        try {
            log.info("Starting daily link health check...");

            int groupInactive = em.createQuery(
                "UPDATE GroupEntity g SET g.isActive = false, g.invalidReason = '链接巡检: 成员为0' " +
                "WHERE g.isActive = true AND (g.memberCount IS NULL OR g.memberCount = 0)")
                .executeUpdate();

            int groupStale = em.createQuery(
                "UPDATE GroupEntity g SET g.isActive = false, g.invalidReason = '链接巡检: 超过30天未更新' " +
                "WHERE g.isActive = true AND g.updatedAt < :cutoff")
                .setParameter("cutoff", LocalDateTime.now().minusDays(30))
                .executeUpdate();

            log.info("Link health check: {} groups inactive, {} groups stale", groupInactive, groupStale);

            int proxyInactive = em.createQuery(
                "UPDATE ProxyEntity p SET p.isActive = false, p.invalidReason = '链接巡检: 在线率过低' " +
                "WHERE p.isActive = true AND p.onlineRate < 10")
                .executeUpdate();
            log.info("Proxy deactivated: {}", proxyInactive);

            log.info("Link health check completed");
        } finally {
            distributedLock.unlock("scheduler:linkHealthCheck");
        }
    }

    /** 每日 02:00 — DeepSeek 画像分析（限频：一次最多分析200人） */
    @Scheduled(cron = "0 0 2 * * ?", zone = "Asia/Shanghai")
    @SuppressWarnings("unchecked")
    public void dailyProfileAnalysis() {
        if (!distributedLock.tryLock("scheduler:dailyProfileAnalysis", LOCK_TIMEOUT_MS)) {
            log.debug("dailyProfileAnalysis: another instance holds the lock, skipping");
            return;
        }
        try {
            if (circuitBreaker.isYellow() || circuitBreaker.isRed()) {
                log.warn("Skipping profile analysis: system in {} mode", circuitBreaker.getCurrentState());
                return;
            }
            log.info("Starting daily DeepSeek profile analysis...");

            LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);

            // 每个用户最多分析20条消息；每次最多200人，防止打爆API
            // 注意：当前为简单分页，用户量增长后需改为有状态分批处理
            List<Long> activeUserIds = em.createQuery(
                "SELECT m.userId FROM MessageEntity m WHERE m.createdAt >= :today " +
                "GROUP BY m.userId HAVING COUNT(m) >= 10")
                .setParameter("today", todayStart)
                .setMaxResults(200)
                .getResultList();

            log.info("Found {} active users for profile analysis (cap=200)", activeUserIds.size());

            for (Long uid : activeUserIds) {
                try {
                    List<String> recentMessages = em.createQuery(
                        "SELECT m.text FROM MessageEntity m WHERE m.userId = :uid AND m.createdAt >= :weekAgo ORDER BY m.createdAt DESC")
                        .setParameter("uid", uid)
                        .setParameter("weekAgo", LocalDateTime.now().minusDays(7))
                        .setMaxResults(20)
                        .getResultList();

                    if (recentMessages.isEmpty()) continue;

                    moderationService.analyzeProfile(String.join("\n---\n", recentMessages));
                    log.debug("Profile analysis submitted for user={} messages={}", uid, recentMessages.size());
                } catch (Exception e) {
                    log.warn("Profile analysis failed for user={}: {}", uid, e.getMessage());
                }
            }

            log.info("Profile analysis completed: {} users analyzed", activeUserIds.size());
        } finally {
            distributedLock.unlock("scheduler:dailyProfileAnalysis");
        }
    }

    /** 每日 03:00 — 信用分自动恢复 */
    @Transactional
    @Scheduled(cron = "0 0 3 * * ?", zone = "Asia/Shanghai")
    public void dailyCreditIncrement() {
        if (!distributedLock.tryLock("scheduler:dailyCreditIncrement", LOCK_TIMEOUT_MS)) {
            log.debug("dailyCreditIncrement: another instance holds the lock, skipping");
            return;
        }
        try {
            log.info("Starting daily credit auto-increment...");

            int normal = em.createQuery(
                "UPDATE UserEntity u SET u.creditScore = LEAST(u.creditScore + :inc, :max) " +
                "WHERE u.creditScore < :max AND u.creditScore > 0 " +
                "AND (u.frozen = false OR (u.frozenUntil IS NOT NULL AND u.frozenUntil < CURRENT_TIMESTAMP)) " +
                "AND (u.createdAt IS NULL OR u.createdAt <= :ageThreshold)")
                .setParameter("inc", dailyAutoIncrement)
                .setParameter("max", maxScore)
                .setParameter("ageThreshold", LocalDateTime.now().minusDays(7))
                .executeUpdate();

            log.info("Daily credit increment: {} established users +1 (new accounts skipped)", normal);
        } finally {
            distributedLock.unlock("scheduler:dailyCreditIncrement");
        }
    }

    /** 每周一 00:00 — 排行榜结算（分页，每次最多处理 5000 人） */
    @Transactional
    @Scheduled(cron = "0 0 0 * * MON", zone = "Asia/Shanghai")
    public void weeklyRankingSettlement() {
        if (!distributedLock.tryLock("scheduler:weeklyRankingSettlement", LOCK_TIMEOUT_MS)) {
            log.debug("weeklyRankingSettlement: another instance holds the lock, skipping");
            return;
        }
        try {
            log.info("Starting weekly ranking settlement...");

            long total = userRepo.count();
            if (total == 0) {
                log.info("No users to settle");
                return;
            }

            int pageSize = 2000;
            int offset = 0;
            int processed = 0;
            boolean hasMore = true;

            while (hasMore) {
                List<Long> userIds = em.createQuery(
                    "SELECT u.userId FROM UserEntity u ORDER BY u.creditScore DESC", Long.class)
                    .setFirstResult(offset)
                    .setMaxResults(pageSize)
                    .getResultList();

                if (userIds.isEmpty()) {
                    hasMore = false;
                    break;
                }

                for (int i = 0; i < userIds.size(); i++) {
                    int rankPos = offset + i;
                    double pct = (double) rankPos / total * 100;
                    long uid = userIds.get(i);
                    int reward = 0;

                    if (pct < 1) {
                        reward = top1Reward;
                    } else if (pct < 5) {
                        reward = top25Reward;
                    } else if (pct <= 15) {
                        reward = top615Reward;
                    } else if (pct >= 85 && pct < 95) {
                        reward = bottom615Penalty;
                    } else if (pct >= 95) {
                        reward = bottom5Penalty;
                    }

                    if (reward != 0) {
                        creditEngine.apply(uid, reward,
                            reward > 0 ? "ranking" : "ranking_penalty", "排行榜结算");
                    }
                }

                processed += userIds.size();
                offset += pageSize;
                em.flush();
                em.clear();
            }

            log.info("Weekly ranking settlement completed: {} users processed", processed);
        } finally {
            distributedLock.unlock("scheduler:weeklyRankingSettlement");
        }
    }

    /** 每周日 04:00 — 标签合规审计 */
    @Transactional
    @Scheduled(cron = "0 0 4 * * SUN", zone = "Asia/Shanghai")
    public void weeklyLabelAudit() {
        if (!distributedLock.tryLock("scheduler:weeklyLabelAudit", LOCK_TIMEOUT_MS)) {
            log.debug("weeklyLabelAudit: another instance holds the lock, skipping");
            return;
        }
        try {
            log.info("Starting weekly label compliance audit...");

            int pageSize = 500;
            int page = 0;
            int totalChecked = 0;
            boolean hasMore = true;

            while (hasMore) {
                List<GroupEntity> labeledGroups = em.createQuery(
                    "FROM GroupEntity g WHERE g.groupLabel IN ('NSFW', 'GAMBLING') AND g.isActive = true",
                    GroupEntity.class)
                    .setFirstResult(page * pageSize)
                    .setMaxResults(pageSize)
                    .getResultList();

                if (labeledGroups.isEmpty()) {
                    hasMore = false;
                    break;
                }

                for (GroupEntity g : labeledGroups) {
                try {
                    List<MessageEntity> samples = em.createQuery(
                        "FROM MessageEntity m WHERE m.groupId = :gid ORDER BY m.createdAt DESC",
                        MessageEntity.class)
                        .setParameter("gid", g.getGroupId())
                        .setMaxResults(50)
                        .getResultList();

                    int hitCount = 0;
                    String keyword = g.getGroupLabel() == GroupEntity.GroupLabel.NSFW ? "色情|NSFW|成人|18+" : "赌博|博彩|赌场|casino";

                    for (MessageEntity m : samples) {
                        if (m.getText() != null && m.getText().matches(".*(" + keyword + ").*")) {
                            hitCount++;
                        }
                    }

                    double matchRate = samples.isEmpty() ? 0 : (double) hitCount / samples.size() * 100;

                    if (matchRate < 60 && !samples.isEmpty()) {
                        g.setLabelAuditViolations(g.getLabelAuditViolations() + 1);
                        log.warn("Label audit fail: group={} label={} matchRate={}% violations={}",
                            g.getGroupId(), g.getGroupLabel(), String.format("%.1f", matchRate),
                            g.getLabelAuditViolations());

                        if (g.getLabelAuditViolations() >= 3) {
                            g.setGroupLabel(GroupEntity.GroupLabel.NONE);
                            g.setLabelReapplyForbidden(true);
                            g.setEnvironmentScore(Math.max(0, g.getEnvironmentScore() - 30));
                            log.warn("Label revoked and banned from reapplying: group={}", g.getGroupId());
                        } else if (g.getLabelAuditViolations() >= 2) {
                            g.setEnvironmentScore(Math.max(0, g.getEnvironmentScore() - 20));
                        } else {
                            g.setEnvironmentScore(Math.max(0, g.getEnvironmentScore() - 10));
                        }
                    } else {
                        if (g.getLabelAuditViolations() > 0) {
                            g.setLabelAuditViolations(g.getLabelAuditViolations() - 1);
                        }
                    }

                    em.merge(g);
                } catch (Exception e) {
                    log.warn("Label audit failed for group {}: {}", g.getGroupId(), e.getMessage());
                }
            }

                em.flush();
                em.clear();
                page++;
                totalChecked += labeledGroups.size();
            }

            log.info("Weekly label compliance audit completed: {} labeled groups checked", totalChecked);
        } finally {
            distributedLock.unlock("scheduler:weeklyLabelAudit");
        }
    }

    /** 每 10 分钟 — 刷新违规模板缓存 */
    @Scheduled(fixedRate = 600000)
    public void refreshTemplateCache() {
        try {
            templateEngine.refreshCache();
        } catch (Exception e) {
            log.warn("Template cache refresh failed: {}", e.getMessage());
        }
    }

    /** 每 30 分钟 — 熔断状态检查（全局 + 群组级） */
    @Scheduled(fixedRate = 1800000)
    @Transactional
    public void circuitBreakerCheck() {
        if (!distributedLock.tryLock("scheduler:circuitBreakerCheck", LOCK_TIMEOUT_MS)) {
            log.debug("circuitBreakerCheck: another instance holds the lock, skipping");
            return;
        }
        try {
            if (circuitBreaker.isYellow()) {
                circuitBreaker.attemptRecovery();
                log.info("Global circuit YELLOW: recovery check triggered");
            }

            try {
                var expiredGroups = em.createQuery(
                    "FROM GroupEntity g WHERE g.circuitBroken = true AND g.circuitRecoverAt IS NOT NULL AND g.circuitRecoverAt <= CURRENT_TIMESTAMP",
                    com.tgf.bot.model.GroupEntity.class)
                    .getResultList();
                for (var g : expiredGroups) {
                    log.info("Auto-recovering group {} circuit break (expired at {})", g.getGroupId(), g.getCircuitRecoverAt());
                    groupBreaker.manualRecover(g.getGroupId());
                }
                if (!expiredGroups.isEmpty()) {
                    log.info("Auto-recovered {} groups from circuit break", expiredGroups.size());
                }
            } catch (Exception e) {
                log.warn("Group circuit recovery check failed: {}", e.getMessage());
            }
        } finally {
            distributedLock.unlock("scheduler:circuitBreakerCheck");
        }
    }
}
