package com.tgf.bot.scheduler;

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
 * 
 * 负责定期执行系统维护任务，包括每日信用分自动恢复、
 * SLA 超时工单升级、冷启动期检查、违规率分析、排行榜结算等。
 * 规格书：第三章(链接巡检)、第四章(画像分析)、第八章(标签审计)、第十六章(排行榜结算)
 * @since 1.0
 */
@Component
public class BotScheduler {

    private static final Logger log = LoggerFactory.getLogger(BotScheduler.class);

    @PersistenceContext
    private EntityManager em;

    private final UserRepository userRepo;
    private final CreditEngine creditEngine;
    private final RankingEngine rankingEngine;
    private final ContentModerationService moderationService;
    private final CircuitBreakerService circuitBreaker;
    private final TicketService ticketService;
    private final CaptchaService captchaService;
    private final GroupCircuitBreakerService groupBreaker;

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
                        RankingEngine rankingEngine, ContentModerationService moderationService,
                        CircuitBreakerService circuitBreaker, TicketService ticketService,
                        CaptchaService captchaService,
                        GroupCircuitBreakerService groupBreaker) {
        this.userRepo = userRepo;
        this.creditEngine = creditEngine;
        this.rankingEngine = rankingEngine;
        this.moderationService = moderationService;
        this.circuitBreaker = circuitBreaker;
        this.ticketService = ticketService;
        this.captchaService = captchaService;
        this.groupBreaker = groupBreaker;
    }

    /** 每日 04:00 — 链接巡检：标记失效群组/机器人/代理 */
    @Scheduled(cron = "0 0 4 * * ?", zone = "Asia/Shanghai")
    @Transactional
    public void linkHealthCheck() {
        log.info("Starting daily link health check...");

        // 群组链接巡检：成员数为 0 或超过 30 天未更新的标记为待检查
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

        // 代理巡检：在线率 < 10% 的代理标记为不活跃
        int proxyInactive = em.createQuery(
            "UPDATE ProxyEntity p SET p.isActive = false, p.invalidReason = '链接巡检: 在线率过低' " +
            "WHERE p.isActive = true AND p.onlineRate < 10")
            .executeUpdate();
        log.info("Proxy deactivated: {}", proxyInactive);

        log.info("Link health check completed");
    }

    /** 每日 02:00 — DeepSeek 画像分析 */
    @SuppressWarnings("unchecked")
    @Scheduled(cron = "0 0 2 * * ?", zone = "Asia/Shanghai")
    public void dailyProfileAnalysis() {
        if (circuitBreaker.isYellow() || circuitBreaker.isRed()) {
            log.warn("Skipping profile analysis: system in {} mode", circuitBreaker.getCurrentState());
            return;
        }
        log.info("Starting daily DeepSeek profile analysis...");

        // 查询当日发言 ≥ 10 条的用户
        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        List<Long> activeUserIds = em.createQuery(
            "SELECT m.userId FROM MessageEntity m WHERE m.messageTime >= :today " +
            "GROUP BY m.userId HAVING COUNT(m) >= 10")
            .setParameter("today", todayStart)
            .setMaxResults(50)
            .getResultList();

        log.info("Found {} active users for profile analysis", activeUserIds.size());

        for (Long uid : activeUserIds) {
            try {
                // 打包最近 20 条跨群组消息
                List<String> recentMessages = em.createQuery(
                    "SELECT m.text FROM MessageEntity m WHERE m.userId = :uid ORDER BY m.messageTime DESC")
                    .setParameter("uid", uid)
                    .setMaxResults(20)
                    .getResultList();

                if (recentMessages.isEmpty()) continue;

                // 调用内容审核服务进行画像分析
                String text = String.join("\n---\n", recentMessages);
                moderationService.analyzeProfile(text);

                log.debug("Profile analysis submitted for user={} messages={}", uid, recentMessages.size());
            } catch (Exception e) {
                log.warn("Profile analysis failed for user={}: {}", uid, e.getMessage());
            }
        }

        log.info("Profile analysis completed: {} users analyzed", activeUserIds.size());
    }

    /** 每日 03:00 — 信用分自动恢复 */
    @Transactional
    @Scheduled(cron = "0 0 3 * * ?", zone = "Asia/Shanghai")
    public void dailyCreditIncrement() {
        log.info("Starting daily credit auto-increment...");

        // 新账号 (<7天) 自动恢复打折 — 防刷
        // 老账号正常每日 +1，新账号每日 +0 或 +1（由折扣规则决定）
        int normal = em.createQuery(
            "UPDATE UserEntity u SET u.creditScore = LEAST(u.creditScore + :inc, :max) " +
            "WHERE u.creditScore < :max AND u.creditScore > 0 AND u.frozen = false " +
            "AND (u.createdAt IS NULL OR u.createdAt <= :ageThreshold)")
            .setParameter("inc", dailyAutoIncrement)
            .setParameter("max", maxScore)
            .setParameter("ageThreshold", LocalDateTime.now().minusDays(7))
            .executeUpdate();

        // 新账号（<7天）每日恢复 0 分（仅恢复，不加分）
        // 阻止黑产通过注册新号刷自动加分
        log.info("Daily credit increment: {} established users +1 (new accounts skipped)", normal);
    }

    /** 每周一 00:00 — 排行榜结算 */
    @Transactional
    @Scheduled(cron = "0 0 0 * * MON", zone = "Asia/Shanghai")
    public void weeklyRankingSettlement() {
        log.info("Starting weekly ranking settlement...");

        long total = userRepo.count();
        if (total == 0) {
            log.info("No users to settle");
            return;
        }

        // 按信用分降序取所有用户ID
        List<Long> userIds = em.createQuery(
            "SELECT u.userId FROM UserEntity u ORDER BY u.creditScore DESC", Long.class)
            .getResultList();

        for (int i = 0; i < userIds.size(); i++) {
            double pct = (double) i / total * 100;
            long uid = userIds.get(i);
            int reward = 0;

            if (pct < 1) {
                reward = top1Reward;
            } else if (pct < 5) {
                reward = top25Reward;
            } else if (pct < 15) {
                reward = top615Reward;
            } else if (pct >= 85 && pct < 95) {
                reward = bottom615Penalty;
            } else if (pct >= 95) {
                reward = bottom5Penalty;
            }

            if (reward != 0) {
                creditEngine.apply(uid, Math.abs(reward),
                    reward > 0 ? "ranking" : "ranking_penalty", "排行榜结算");
            }
        }

        log.info("Weekly ranking settlement completed: {} users processed", userIds.size());
    }

    /** 每周日 04:00 — 标签合规审计 */
    @Transactional
    @Scheduled(cron = "0 0 4 * * SUN", zone = "Asia/Shanghai")
    public void weeklyLabelAudit() {
        log.info("Starting weekly label compliance audit...");

        // 获取所有有标签的群组
        @SuppressWarnings("unchecked")
        List<GroupEntity> labeledGroups = em.createQuery(
            "FROM GroupEntity g WHERE g.groupLabel IN ('NSFW', 'GAMBLING') AND g.isActive = true")
            .getResultList();

        for (GroupEntity g : labeledGroups) {
            try {
                // 采样该群组最近 50 条消息
                @SuppressWarnings("unchecked")
                List<MessageEntity> samples = em.createQuery(
                    "FROM MessageEntity m WHERE m.chatId = :gid ORDER BY m.messageTime DESC")
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

                // 匹配标准：对应内容占比需 ≥ 60%
                double matchRate = samples.isEmpty() ? 0 : (double) hitCount / samples.size() * 100;

                if (matchRate < 60 && !samples.isEmpty()) {
                    g.setLabelAuditViolations(g.getLabelAuditViolations() + 1);
                    log.warn("Label audit fail: group={} label={} matchRate={}% violations={}",
                        g.getGroupId(), g.getGroupLabel(), String.format("%.1f", matchRate),
                        g.getLabelAuditViolations());

                    // 三次违规阶梯惩罚
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
                    // 合规，违规计数递减
                    if (g.getLabelAuditViolations() > 0) {
                        g.setLabelAuditViolations(g.getLabelAuditViolations() - 1);
                    }
                }

                em.merge(g);
            } catch (Exception e) {
                log.warn("Label audit failed for group {}: {}", g.getGroupId(), e.getMessage());
            }
        }

        log.info("Label audit completed: {} groups audited", labeledGroups.size());
    }

    /** 每 30 分钟 — 熔断状态检查（全局 + 群组级） */
    @Scheduled(fixedRate = 1800000)
    @Transactional
    public void circuitBreakerCheck() {
        // L3 全局熔断检查
        if (circuitBreaker.isYellow()) {
            circuitBreaker.attemptRecovery();
            log.info("Global circuit YELLOW: recovery check triggered");
        }

        // L1 群组级熔断恢复：恢复已过熔断期的群组
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
    }
}
