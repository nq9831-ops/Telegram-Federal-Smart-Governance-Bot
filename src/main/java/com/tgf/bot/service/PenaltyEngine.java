package com.tgf.bot.service;

import com.tgf.bot.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * PenaltyEngine — 处罚引擎。
 * 
 * 四级内容分类与安全港处罚矩阵，
 * 所有处罚由 DeepSeek 分类结果驱动，与群组标签联动决策。
 * @since 1.0
 */
@Service
public class PenaltyEngine {

    private static final Logger log = LoggerFactory.getLogger(PenaltyEngine.class);

    private final CreditEngine creditEngine;
    private final TicketService ticketService;

    public PenaltyEngine(CreditEngine creditEngine, TicketService ticketService) {
        this.creditEngine = creditEngine;
        this.ticketService = ticketService;
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

        return new PenaltyResult("死刑：信用分清零，永久拉黑，所有群组踢出", true);
    }

    /** 政治煽动分裂 - 死刑，需高级审核官确认 */
    private PenaltyResult handlePolitical(UserEntity user, String messageText) {
        // 生成死罪复核工单
        ticketService.createTicket("death_review", user, messageText);

        return new PenaltyResult("需人工复核：已生成死罪复核工单，SLA 30分钟", true);
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

        return new PenaltyResult("普通广告：扣" + basePenalty + "分", true);
    }

    private void recordDeathInSafeHaven(GroupEntity group) {
        // 由调用方维护群组的死罪计数
    }

    /**
     * 安全港滥用检查
     */
    public boolean checkSafeHavenAbuse(UserEntity user, GroupEntity group) {
        log.warn("checkSafeHavenAbuse called but not yet implemented via DeepSeek profile analysis");
        return false;
    }

    public record PenaltyResult(String description, boolean penalized) {}
}
