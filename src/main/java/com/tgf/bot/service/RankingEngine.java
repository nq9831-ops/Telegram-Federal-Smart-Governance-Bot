package com.tgf.bot.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * RankingEngine — 排行榜引擎。
 * 
 * 基于 Redis ZSet 实现四大排行榜（用户/群组/机器人/代理），
 * 支持每周结算与多维度评分。
 * @since 1.0
 */
@Service
public class RankingEngine {

    private final StringRedisTemplate redis;

    private static final String KEY_USER = "rank:user";
    private static final String KEY_GROUP = "rank:group";

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

    public List<Map.Entry<String, Double>> getRanking(String key, int top) {
        var set = redis.opsForZSet().reverseRangeWithScores(key, 0, top - 1);
        if (set == null) return List.of();
        return set.stream()
            .map(t -> Map.entry(t.getValue().toString(), t.getScore()))
            .toList();
    }
}
