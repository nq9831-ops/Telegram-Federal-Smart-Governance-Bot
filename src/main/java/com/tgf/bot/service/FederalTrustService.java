package com.tgf.bot.service;

import com.tgf.bot.model.UserEntity;
import com.tgf.bot.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * FederalTrustService — 联邦信任与黑产防刷服务。
 * 
 * 提供动态全局禁言阈值、黑产刷分检测、账号年龄约束等能力，
 * 防止批量小号、互相邀请、挂机养号等黑产行为。
 * @since 1.0
 */
@Service
public class FederalTrustService {

    private static final Logger log = LoggerFactory.getLogger(FederalTrustService.class);

    @PersistenceContext
    private EntityManager em;

    private final UserRepository userRepo;
    private final AggregatedNotifier notifier;

    public FederalTrustService(UserRepository userRepo, AggregatedNotifier notifier) {
        this.userRepo = userRepo;
        this.notifier = notifier;
    }

    // ==========================================
    //  1. 动态全局禁言阈值
    // ==========================================

    /**
     * 根据用户信任等级计算实际的全局禁言阈值。
     *
     * 信任等级影响因素：
     *   - 基础信用分（权重最大）
     *   - 账号注册天数 (>=14 天逐步解锁完整阈值)
     *   - 是否高级审核官（降 10 分阈值）
     *   - 近 7 天违规次数（升高阈值）
     *
     * @return 该用户实际禁言阈值（低于此分数则禁言）。默认 50。
     */
    public int getEffectiveMuteThreshold(long userId) {
        UserEntity user = userRepo.findById(userId).orElse(null);
        if (user == null) return 50;

        int baseMute = 50;

        // ----- 加分项（更信任 → 更晚禁言）-----

        // 1. 信用分等级加成（高分用户更可信）
        int creditScore = user.getCreditScore();
        if (creditScore >= 80) baseMute -= 15;      // 钻石信誉 → 35 分才禁言
        else if (creditScore >= 60) baseMute -= 8;   // 黄金信誉 → 42 分才禁言
        else if (creditScore >= 40) baseMute -= 3;   // 白银信誉 → 47 分才禁言

        // 2. 账号年龄加成（老号更可信）
        LocalDateTime createdAt = user.getCreatedAt();
        if (createdAt != null) {
            long days = java.time.Duration.between(createdAt, LocalDateTime.now()).toDays();
            if (days >= 30) baseMute -= 5;            // 1个月以上 → 45
            else if (days < 7) baseMute += 5;          // 新号 (<7天) → 55（更容易被禁言）
        }

        // 3. 审核官/群主身份降权
        if (user.getIsReviewer()) baseMute -= 10;      // 高级审核官 → 再降 10

        // ----- 扣分项（更不信任 → 更早禁言）-----

        // 4. 近 7 天违规次数
        long recentViolations = countRecentViolations(userId, 7);
        if (recentViolations >= 5) baseMute += 10;     // 频繁违规 → 更容易被禁言
        else if (recentViolations >= 3) baseMute += 5;

        // 5. 种子用户保护（管理员标记的安全用户，永远不禁言）
        if (user.getTrustedSeed()) return 0;           // 种子用户 → 不会被自动禁言

        return Math.max(5, Math.min(70, baseMute));
    }

    /**
     * 替代 creditEngine.shouldMuteGlobally() — 使用动态阈值
     */
    public boolean shouldMute(long userId) {
        UserEntity user = userRepo.findById(userId).orElse(null);
        if (user == null) return false;
        int threshold = getEffectiveMuteThreshold(userId);
        return user.getCreditScore() < threshold;
    }

    // ==========================================
    //  2. 黑产防刷：加分权评估
    // ==========================================

    /**
     * 计算加分是否应被折扣（反黑产）。
     *
     * @return 实际应加的分数（可能被折扣或归零）
     */
    public int applyRewardCredit(long userId, int baseReward, String actionType) {
        UserEntity user = userRepo.findById(userId).orElse(null);
        if (user == null) return baseReward;

        double discount = 1.0;  // 折扣系数

        // 1. 账号年龄折扣
        LocalDateTime createdAt = user.getCreatedAt();
        if (createdAt != null) {
            long days = java.time.Duration.between(createdAt, LocalDateTime.now()).toDays();
            if (days < 3) discount -= 0.5;     // 3天内 → 只有 50%
            else if (days < 7) discount -= 0.3; // 7天内 → 70%
            else if (days < 14) discount -= 0.1; // 14天内 → 90%
        }

        // 2. 每日奖励上限检查
        int todayReward = getTodayRewardTotal(userId);
        int dailyRewardCap = 5;  // 每日奖励总分上限（不含自动恢复）
        if (todayReward >= dailyRewardCap) {
            log.info("Reward cap reached for user {}: {} >= {} (action: {})",
                userId, todayReward, dailyRewardCap, actionType);
            return 0;  // 超出上限，拒绝
        }

        // 3. 邀请关联度检测：检查被邀请人是否活跃
        if ("invite_reward".equals(actionType) && discount > 0) {
            long invitedBy = user.getInvitedBy() != null ? user.getInvitedBy() : 0;
            if (invitedBy > 0 && !isUserActive(invitedBy)) {
                // 邀请者不活跃 → 可能批量注册小号
                discount -= 0.3;
                log.warn("Suspicious invite chain: user={} invited by inactive {}", userId, invitedBy);
            }
        }

        // 4. 多账号关联检测（同 IP 批量注册）
        if (hasSuspiciousRegistration(userId)) {
            discount -= 0.5;
            log.warn("Suspicious registration pattern for user {}", userId);
        }

        // 最终奖励
        int finalReward = (int) Math.round(baseReward * Math.max(0, discount));
        if (finalReward < baseReward && finalReward > 0) {
            log.info("Reward discounted for user {}: {} -> {} ({}x) action={}",
                userId, baseReward, finalReward, String.format("%.2f", discount), actionType);
        }
        return finalReward;
    }

    // ==========================================
    //  3. 黑产图谱检测
    // ==========================================

    /**
     * 检测是否有多账号注册嫌疑（同 IP、推荐链异常）。
     */
    public boolean hasSuspiciousRegistration(long userId) {
        // 简易检测：同一 IP 注册超过 3 个账号
        String ip = getRegistrationIp(userId);
        if (ip == null || ip.isEmpty() || "unknown".equals(ip)) return false;

        long sameIpCount = (long) em.createQuery(
            "SELECT COUNT(u) FROM UserEntity u WHERE u.registrationIp = :ip AND u.createdAt >= :since")
            .setParameter("ip", ip)
            .setParameter("since", LocalDateTime.now().minusDays(7))
            .getSingleResult();

        return sameIpCount >= 3;
    }

    /**
     * 检查邀请链异常：A 邀请 B，B 邀请 C，B 无独立行为
     */
    public boolean hasChainRegistrationSuspicion(long userId) {
        UserEntity user = userRepo.findById(userId).orElse(null);
        if (user == null || user.getInvitedBy() == null || user.getInvitedBy() == 0) return false;

        long inviterId = user.getInvitedBy();
        UserEntity inviter = userRepo.findById(inviterId).orElse(null);
        if (inviter == null) return false;

        // 邀请者也是被邀请的（形成链条）
        if (inviter.getInvitedBy() == null || inviter.getInvitedBy() == 0) return false;

        // 检查邀请者是否活跃
        if (!isUserActive(inviterId)) return true;

        return false;
    }

    /**
     * 获取今日已获得的奖励分总数
     */
    private int getTodayRewardTotal(long userId) {
        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        try {
            var result = em.createQuery(
                "SELECT COALESCE(SUM(a.afterValue - a.beforeValue), 0) FROM AuditLogEntity a " +
                "WHERE a.targetUserId = :uid AND a.actionType IN ('reward', 'invite_reward', 'rating_reward') " +
                "AND a.createdAt >= :since")
                .setParameter("uid", userId)
                .setParameter("since", todayStart)
                .getSingleResult();
            return result != null ? ((Number) result).intValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 近 N 天违规次数
     */
    private long countRecentViolations(long userId, int days) {
        try {
            return (long) em.createQuery(
                "SELECT COUNT(a) FROM AuditLogEntity a " +
                "WHERE a.targetUserId = :uid AND a.actionType = 'punish' " +
                "AND a.createdAt >= :since")
                .setParameter("uid", userId)
                .setParameter("since", LocalDateTime.now().minusDays(days))
                .getSingleResult();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 检查用户是否活跃（有消息记录或信用分>初始分）
     */
    private boolean isUserActive(long userId) {
        UserEntity user = userRepo.findById(userId).orElse(null);
        if (user == null) return false;
        return user.getCreditScore() > 100 || user.getCreatedAt() != null
            && java.time.Duration.between(user.getCreatedAt(), LocalDateTime.now()).toDays() >= 3;
    }

    /**
     * 获取注册 IP（需要 UserEntity 有 registrationIp 字段）
     */
    private String getRegistrationIp(long userId) {
        try {
            return (String) em.createQuery(
                "SELECT u.registrationIp FROM UserEntity u WHERE u.userId = :uid")
                .setParameter("uid", userId)
                .getSingleResult();
        } catch (Exception e) {
            return null;
        }
    }
}
