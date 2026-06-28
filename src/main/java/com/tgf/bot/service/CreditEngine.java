package com.tgf.bot.service;

import com.tgf.bot.model.*;
import java.util.List;
import com.tgf.bot.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * CreditEngine — 信用分引擎。
 * 
 * 整个系统的核心信用分管理组件，所有处罚/奖励/冻结/等级判定统一入口，
 * 自动生成审计日志并联动联邦信任机制。
 * @since 1.0
 */
@Service
public class CreditEngine {

    private static final Logger log = LoggerFactory.getLogger(CreditEngine.class);

    private final UserRepository userRepo;
    private final TicketService ticketService;
    private final FederalTrustService federalTrust;
    private final AggregatedNotifier notifier;

    @PersistenceContext
    private EntityManager em;

    public CreditEngine(UserRepository userRepo, TicketService ticketService,
                        FederalTrustService federalTrust, AggregatedNotifier notifier) {
        this.userRepo = userRepo;
        this.ticketService = ticketService;
        this.federalTrust = federalTrust;
        this.notifier = notifier;
    }

    /** 执行信用分变更，自动写审计日志 */
    @Transactional
    public int apply(long userId, int change, String actionType, String reason,
                     String operatorType, Long operatorId) {
        var user = userRepo.findById(userId).orElse(null);
        if (user == null) {
            log.warn("User not found: {}", userId);
            return 0;
        }

        if (user.isFrozen() && change < 0) {
            log.info("User {} frozen, penalty skipped: {}", userId, reason);
            return user.getCreditScore();
        }

        // 加分行为 — 联邦信任机制介入（黑产防刷）
        if (change > 0 && !"daily_auto_increment".equals(actionType)) {
            int discounted = federalTrust.applyRewardCredit(userId, change, actionType);
            if (discounted == 0) {
                log.info("Reward blocked by trust engine: user={} action={}", userId, actionType);
                return user.getCreditScore();  // 被拦截
            }
            if (discounted != change) {
                log.info("Reward discounted: user={} {}->{} action={}", userId, change, discounted, actionType);
                change = discounted;
            }
        }

        int before = user.getCreditScore();
        int after = Math.max(0, Math.min(100, before + change));
        user.setCreditScore(after);
        userRepo.save(user);

        var audit = new AuditLogEntity();
        audit.setOperatorType(operatorType);
        audit.setOperatorUserId(operatorId != null ? operatorId : 0);
        audit.setActionType(actionType);
        audit.setTargetUserId(userId);
        audit.setBeforeValue(before);
        audit.setAfterValue(after);
        audit.setReason(reason);
        em.persist(audit);

        // 信用分异动检查 + 聚合通知
        checkCreditAnomaly(userId, before, after, actionType);

        // 聚合通知推送
        if (Math.abs(after - before) >= 15) {
            notifier.pushCreditAnomaly(userId, before, after, reason);
        }
        if (after == 0 && before > 0) {
            notifier.pushDeathPenalty(userId, reason);
        }

        log.info("Credit changed: user={} {}->{} ({}): {}",
            userId, before, after, change, reason);

        return after;
    }

    public int apply(long userId, int change, String actionType, String reason) {
        return apply(userId, change, actionType, reason, "system", null);
    }

    public int applyByDeepSeek(long userId, int change, String actionType, String reason) {
        return apply(userId, change, actionType, reason, "deepseek", null);
    }

    /** 死刑 - 信用分清零 */
    @Transactional
    public int deathPenalty(long userId, String reason, String operatorType, Long operatorId) {
        int before = userRepo.findById(userId).map(UserEntity::getCreditScore).orElse(0);
        var result = apply(userId, -before, "punish", "死刑: " + reason, operatorType, operatorId);
        log.warn("DEATH PENALTY: user={} reason={}", userId, reason);
        return result;
    }

    public int deathPenalty(long userId, String reason) {
        return deathPenalty(userId, reason, "system", null);
    }

    public boolean shouldMuteGlobally(int creditScore) {
        return creditScore < 50;
    }

    public boolean shouldFreezePrivileges(int creditScore) {
        return creditScore < 60;
    }

    /** 信用分临时冻结 */
    @Transactional
    public void freeze(long userId, int hours) {
        var user = userRepo.findById(userId).orElse(null);
        if (user == null) return;
        user.setFrozen(true);
        user.setFrozenUntil(LocalDateTime.now().plusHours(hours));
        userRepo.save(user);
        log.warn("Credit frozen: user={} for {}h", userId, hours);
    }

    @Transactional
    public void unfreeze(long userId) {
        var user = userRepo.findById(userId).orElse(null);
        if (user == null) return;
        user.setFrozen(false);
        user.setFrozenUntil(null);
        userRepo.save(user);
        log.info("Credit unfrozen: user={}", userId);
    }

    /** 信用分异动告警（24h变化超±30分） */
    private void checkCreditAnomaly(long userId, int before, int after, String actionType) {
        int diff = Math.abs(after - before);
        if (diff >= 30) {
            // 检查是否由系统自动奖励引起（豁免）
            boolean isAutoReward = List.of("daily_auto", "ranking", "invite_reward", "rating_reward")
                .contains(actionType);
            if (!isAutoReward) {
                log.warn("CREDIT ANOMALY: user={} change={}pt ({}) action={}",
                    userId, diff, before + "->" + after, actionType);
                // 生成告警工单
                try {
                    var user = userRepo.findById(userId).orElse(null);
                    if (user != null) {
                        ticketService.createTicket("contact_admin", user,
                            String.format("信用分异动告警: %d→%d (变%+d分) 原因: %s",
                                before, after, diff, actionType));
                    }
                } catch (Exception e) {
                    log.warn("Failed to create anomaly alert ticket: {}", e.getMessage());
                }
            }
        }
    }

    // ====== 等级（规格书第十四章） ======

    public enum Rank {
        DIAMOND(90, 100, "钻石信誉"),
        GOLD(70, 89, "黄金信誉"),
        SILVER(50, 69, "白银信誉"),
        BRONZE(30, 49, "青铜信誉"),
        RESTRICTED(0, 29, "受限用户");

        public final int min;
        public final int max;
        public final String label;

        Rank(int min, int max, String label) {
            this.min = min; this.max = max; this.label = label;
        }

        public static Rank of(int score) {
            for (Rank r : values()) {
                if (score >= r.min && score <= r.max) return r;
            }
            return RESTRICTED;
        }
    }

    /** 每日自动加分 */
    @Transactional
    public void dailyAutoIncrementAll() {
        int updated = em.createQuery(
            "UPDATE UserEntity u SET u.creditScore = LEAST(100, u.creditScore + 1) " +
            "WHERE u.creditScore < 100 AND u.isFrozen = false")
            .executeUpdate();
        log.info("Daily auto-increment applied to {} users", updated);
    }

    public int getInitialScore() {
        return 100;
    }
}
