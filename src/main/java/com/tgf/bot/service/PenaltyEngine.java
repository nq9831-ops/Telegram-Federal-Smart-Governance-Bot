package com.tgf.bot.service;

import com.tgf.bot.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * PenaltyEngine — 处罚引擎。
 *
 * 四级内容分类与安全港处罚矩阵，所有处罚由 {@link DeepSeekResult} 分类结果驱动，
 * 与群组标签（{@link com.tgf.bot.model.GroupEntity.GroupLabel}）联动决策。
 *
 * <p>处罚矩阵：</p>
 * <ul>
 *   <li>诈骗（scam）→ 死刑（信用分清零，永久拉黑）</li>
 *   <li>政治煽动（political）→ 生成死罪复核工单，人工确认</li>
 *   <li>色情（porn）→ 安全港（NSFW 群组豁免），跨域扣 30 分</li>
 *   <li>赌博（gambling）→ 安全港（GAMBLING 群组豁免），跨域扣 20 分</li>
 *   <li>普通广告（ad）→ 认证广告商豁免（有配额），普通用户扣 5-10 分</li>
 * </ul>
 *
 * <p>安全港机制：当群组拥有特殊标签（NSFW/GAMBLING）时，对应类型内容不触发处罚，
 * 但诈骗/政治内容零容忍，即使在安全港群组也执行死刑。</p>
 *
 * <p>依赖：</p>
 * <ul>
 *   <li>{@link CreditEngine} — 执行信用分变更和死刑</li>
 *   <li>{@link TicketService} — 生成死罪复核工单</li>
 *   <li>{@link GroupManagementService} — 安全港死罪累计撤销标签</li>
 *   <li>{@link com.tgf.bot.repository.UserRepository} — 广告计数持久化</li>
 *   <li>{@link StringRedisTemplate} — 安全港死罪计数器（24h 窗口）</li>
 * </ul>
 *
 * <p>被引用：</p>
 * <ul>
 *   <li>{@link GroupHandler} — 群组消息审核后调用 execute()</li>
 * </ul>
 *
 * @since 1.0
 */
@Service
public class PenaltyEngine {

    private static final Logger log = LoggerFactory.getLogger(PenaltyEngine.class);

    private final CreditEngine creditEngine;
    private final TicketService ticketService;
    private final GroupManagementService groupManagementService;
    private final StringRedisTemplate redis;
    private final com.tgf.bot.repository.UserRepository userRepo;

    public PenaltyEngine(CreditEngine creditEngine, TicketService ticketService,
                         GroupManagementService groupManagementService, StringRedisTemplate redis,
                         com.tgf.bot.repository.UserRepository userRepo) {
        this.creditEngine = creditEngine;
        this.ticketService = ticketService;
        this.groupManagementService = groupManagementService;
        this.redis = redis;
        this.userRepo = userRepo;
    }

    public PenaltyResult execute(DeepSeekResult result, UserEntity user, GroupEntity group, String messageText) {
        if (result == null || !result.isViolation()) {
            return new PenaltyResult("放行", false);
        }

        GroupEntity.GroupLabel groupLabel = group != null ? group.getGroupLabel() : GroupEntity.GroupLabel.NONE;

        // 安全港检查
        if (result.isScam()) {
            return handleScam(user, group, messageText);
        }
        if (result.isPolitical()) {
            return handlePolitical(user, messageText);
        }
        if (result.isPorn()) {
            return handlePorn(user, groupLabel, messageText);
        }
        if (result.isGambling()) {
            return handleGambling(user, groupLabel, messageText);
        }
        if (result.isAd()) {
            return handleAd(user, group, result, messageText);
        }

        return new PenaltyResult("放行", false);
    }

    /** 诈骗 - 死刑，不豁免 */
    private PenaltyResult handleScam(UserEntity user, GroupEntity group, String messageText) {
        creditEngine.deathPenalty(user.getUserId(), "发送诈骗内容");
        user.setDeepseekRiskLevel(UserEntity.RiskLevel.DEATH);

        // 记录安全港群组死罪
        if (group != null && group.getGroupLabel() != GroupEntity.GroupLabel.NONE) {
            recordDeathInSafeHaven(group);
        }

        return new PenaltyResult("死刑：信用分清零，永久拉黑，所有群组踢出", true, true);
    }

    /** 政治煽动分裂 - 死刑，需高级审核官确认 */
    private PenaltyResult handlePolitical(UserEntity user, String messageText) {
        // 生成死罪复核工单
        ticketService.createTicket("death_review", user, messageText);

        return new PenaltyResult("需人工复核：已生成死罪复核工单，SLA 30分钟", true, true);
    }

    /** 色情 - 安全港豁免 */
    private PenaltyResult handlePorn(UserEntity user, GroupEntity.GroupLabel groupLabel, String messageText) {
        if (groupLabel == GroupEntity.GroupLabel.NSFW) {
            return new PenaltyResult("NSFW群组，安全港豁免", false);
        }

            // int before = user.getCreditScore();  // unused
        creditEngine.apply(user.getUserId(), -30, "punish", "色情跨域扣分");
        user.setDeepseekRiskLevel(UserEntity.RiskLevel.DANGER);

        return new PenaltyResult("色情跨域：扣30分，标记ADULT标签，限制普通群组发言", true);
    }

    /** 赌博 - 安全港豁免 */
    private PenaltyResult handleGambling(UserEntity user, GroupEntity.GroupLabel groupLabel, String messageText) {
        if (groupLabel == GroupEntity.GroupLabel.GAMBLING) {
            return new PenaltyResult("GAMBLING群组，安全港豁免", false);
        }

        creditEngine.apply(user.getUserId(), -20, "punish", "赌博跨域扣分");
        user.setDeepseekRiskLevel(UserEntity.RiskLevel.WATCH);

        return new PenaltyResult("赌博跨域：扣20分，标记GAMBLING标签，非博彩群组消息撤回", true);
    }

    /** 普通广告 - 认证豁免+配额限制 */
    private PenaltyResult handleAd(UserEntity user, GroupEntity group, DeepSeekResult result, String messageText) {
        // 认证广告商豁免
        if (user.isCertifiedAdvertiser() && user.getCertExpireAt() != null
            && user.getCertExpireAt().isAfter(LocalDateTime.now())) {

            // 配额检查
            if (group != null) {
                int dailyAd = user.getDailyAdCount();

                // 钻石信誉广告配额翻倍
                int quota = 3;
                var rank = CreditEngine.Rank.of(user.getCreditScore());
                if (rank == CreditEngine.Rank.DIAMOND) {
                    quota = 6; // 钻石翻倍
                }

                if (dailyAd >= quota) {
                    creditEngine.apply(user.getUserId(), -10, "punish", "广告超额扣分");
                    return new PenaltyResult("广告超额：撤回并扣10分", true);
                }
            }
            return new PenaltyResult("认证广告商白名单豁免", false);
        }

        // 普通用户广告扣分
        int basePenalty = 5;
        if (result.getConfidence() >= 0.85) basePenalty = 10;
        creditEngine.apply(user.getUserId(), -basePenalty, "punish", "普通广告扣" + basePenalty + "分");

        // 记录广告计数（用于认证广告商配额检查）
        if (group != null) {
            try {
                user.setDailyAdCount(user.getDailyAdCount() + 1);
                userRepo.save(user);
            } catch (Exception e) {
                log.warn("Failed to persist dailyAdCount for user {}: {}", user.getUserId(), e.getMessage());
            }
        }

        return new PenaltyResult("普通广告：扣" + basePenalty + "分", true);
    }

    private void recordDeathInSafeHaven(GroupEntity group) {
        // Redis 计数器：safe_haven_death:{chatId}，24h 窗口内累计
        String key = "safe_haven_death:" + group.getGroupId();
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            // Redis 故障：安全港计数不可靠，记录日志但不执行惩罚
            // （宁可漏判也不误判：群组标签不因 Redis 故障被错误撤销）
            log.error("Redis INCR failed for safe_haven_death key={}, skipping penalty", key);
            return;
        }
        if (count == 1L) {
            redis.expire(key, Duration.ofHours(24));
        }
        int deathCount = count.intValue();
        log.warn("Safe haven death recorded for group={} count={}/3", group.getGroupId(), deathCount);
        // 累计3条死罪自动触发撤销标签
        if (deathCount >= 3) {
            String result = groupManagementService.handleDeathInSafeHaven(group.getGroupId(), deathCount);
            if (!result.isEmpty()) {
                redis.delete(key); // 处罚后重置计数
                log.warn("Safe haven penalty triggered: {}", result);
            }
        }
    }

    /**
     * 安全港滥用检查
     */
    public boolean checkSafeHavenAbuse(UserEntity user, GroupEntity group) {
        log.warn("checkSafeHavenAbuse called but not yet implemented via DeepSeek profile analysis");
        return false;
    }

    public record PenaltyResult(String description, boolean penalized, boolean deathPenalty) {
        public PenaltyResult(String description, boolean penalized) {
            this(description, penalized, false);
        }
    }
}
