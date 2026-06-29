package com.tgf.bot.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * RankingEngine — 排行榜引擎。
 *
 * 基于 {@link StringRedisTemplate} 的 ZSet 实现四大排行榜（用户/群组/机器人/代理），
 * 支持每周结算与多维度评分。
 *
 * <p>排行榜维度：</p>
 * <ul>
 *   <li>用户信誉榜（rank:user）— 信用分×0.7 + 活跃度×0.2 + 档案完善度×0.1</li>
 *   <li>群组环境榜（rank:group）— 环境分×0.6 + 成员信用均分×0.2 + 标签合规×0.2</li>
 *   <li>机器人服务榜（rank:bot）— 评分×0.8 + 注册时长系数×0.2</li>
 *   <li>代理稳定榜（rank:proxy）— 评分×0.7 + 活跃度×0.3</li>
 * </ul>
 *
 * <p>依赖：</p>
 * <ul>
 *   <li>{@link StringRedisTemplate} — Redis ZSet 存储排行榜数据</li>
 * </ul>
 *
 * <p>被引用：</p>
 * <ul>
 *   <li>{@link MiniAppController} — API 层排行榜查询和评分后更新</li>
 *   <li>{@link BotScheduler} — 每周排行榜结算</li>
 * </ul>
 *
 * @since 1.0
 */
@Service
public class RankingEngine {

    private final StringRedisTemplate redis;

    static final String KEY_USER = "rank:user";
    static final String KEY_GROUP = "rank:group";
    static final String KEY_BOT = "rank:bot";
    static final String KEY_PROXY = "rank:proxy";

    public RankingEngine(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void updateUserScore(Long userId, int creditScore, int activity, int profileCompleteness) {
        double score = creditScore * 0.7 + activity * 0.2 + profileCompleteness * 0.1;
        redis.opsForZSet().add(KEY_USER, String.valueOf(userId), score);
    }

    public void updateGroupScore(Long groupId, int envScore, int avgMemberCredit, int labelCompliance) {
        double score = envScore * 0.6 + avgMemberCredit * 0.2 + labelCompliance * 0.2;
        redis.opsForZSet().add(KEY_GROUP, String.valueOf(groupId), score);
    }

    /**
     * 更新机器人排行分（评分 × 0.8 + 注册时长系数 × 0.2）。
     * @param botId 机器人 ID（BotEntity.id）
     * @param avgRating 平均评分 1-5
     * @param daysSinceCreated 自注册以来的天数
     */
    public void updateBotScore(Long botId, double avgRating, int daysSinceCreated) {
        double ageFactor = Math.min(1.0, daysSinceCreated / 365.0);  // 1年满系数
        double score = avgRating * 20 * 0.8 + ageFactor * 20 * 0.2;  // 归一化到 0-100
        redis.opsForZSet().add(KEY_BOT, String.valueOf(botId), score);
    }

    /**
     * 更新代理排行分（评分 × 0.7 + 活跃度 × 0.3）。
     * @param proxyId 代理 ID（ProxyEntity.id）
     * @param avgRating 平均评分 1-5
     * @param activityFactor 活跃度 0.0-1.0
     */
    public void updateProxyScore(Long proxyId, double avgRating, double activityFactor) {
        double score = avgRating * 20 * 0.7 + activityFactor * 30 * 0.3;  // 归一化到 0-100
        redis.opsForZSet().add(KEY_PROXY, String.valueOf(proxyId), score);
    }

    public List<Map.Entry<String, Double>> getRanking(String key, int top) {
        var set = redis.opsForZSet().reverseRangeWithScores(key, 0, top - 1);
        if (set == null) return List.of();
        return set.stream()
            .map(t -> Map.entry(t.getValue().toString(), t.getScore()))
            .toList();
    }

    /** 简化版：只更新核心分（适用于轻量场景） */
    public void updateUserScore(Long userId, int creditScore) {
        updateUserScore(userId, creditScore, 0, 0);
    }

    public void updateGroupScore(Long groupId, int envScore) {
        updateGroupScore(groupId, envScore, 0, 0);
    }

    public void updateProxyScore(Long proxyId, double avgRating) {
        updateProxyScore(proxyId, avgRating, 0.0);
    }

    public void updateBotScore(Long botId, double avgRating) {
        updateBotScore(botId, avgRating, 0);
    }

}
