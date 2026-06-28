package com.tgf.bot.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.AggregationRange;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.tgf.bot.config.ConcurrencyGuard;
import com.tgf.bot.model.RatingRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * RatingService — 评分服务。
 * 
 * 基于 Elasticsearch 存储的评分系统，覆盖用户/群组/机器人/代理四种实体，
 * 包含验证码防作弊、频率限制和评分奖励。
 * @since 1.0
 */
@Service
public class RatingService {

    private static final Logger log = LoggerFactory.getLogger(RatingService.class);

    @Value("${elasticsearch.rating-index:rating_v2}")
    private String ratingIndex;

    private final ElasticsearchClient es;
    private final CaptchaService captchaService;
    private final CreditEngine creditEngine;
    private final ConcurrencyGuard concurrencyGuard;

    public RatingService(ElasticsearchClient es, CaptchaService captchaService, CreditEngine creditEngine,
                         ConcurrencyGuard concurrencyGuard) {
        this.es = es;
        this.captchaService = captchaService;
        this.creditEngine = creditEngine;
        this.concurrencyGuard = concurrencyGuard;
    }

    public record RatingResult(boolean success, String message) {}

    public RatingResult preCheck(int entityType, Long entityId, Long userId, String ip) {
        if (entityId == null || userId == null) {
            return new RatingResult(false, "参数不完整");
        }

        RatingRecord existing = getUserRating(entityType, entityId, userId);

        if (existing == null) {
            Long lastTime = getLastRatingTime(userId);
            if (lastTime != null && System.currentTimeMillis() - lastTime < 60000) {
                return new RatingResult(false, "评分太频繁，请稍后再试");
            }
        }

        return new RatingResult(true, "");
    }

    public RatingResult rate(int entityType, Long entityId, Long userId, int score, String ip, String captchaId, String captchaInput) {
        if (score < 1 || score > 5) {
            return new RatingResult(false, "评分必须在1~5星之间");
        }

        if (captchaId != null && captchaInput != null) {
            if (!captchaService.verify(captchaId, captchaInput)) {
                return new RatingResult(false, "验证码错误或已过期");
            }
        }

        // 先检查 ES 写入限流，再执行加分（防止限流丢评分但加分已执行）
        boolean acquired = concurrencyGuard.tryAcquireEsWrite();
        if (!acquired) {
            log.warn("ES write concurrency limit reached, rating dropped for user={}", userId);
            return new RatingResult(false, "系统繁忙，请稍后再试");
        }

        try {
            RatingRecord existing = getUserRating(entityType, entityId, userId);
            if (existing != null) {
                existing.setScore(score);
                existing.setUpdateTime(System.currentTimeMillis());
                existing.setIp(ip);
                saveRatingAcquired(existing);
            } else {
                RatingRecord r = new RatingRecord();
                r.setId(UUID.randomUUID().toString());
                r.setEntityType(entityType);
                r.setEntityId(entityId);
                r.setUserId(userId);
                r.setScore(score);
                r.setIp(ip);
                r.setCaptchaId(captchaId);
                r.setCreateTime(System.currentTimeMillis());
                r.setUpdateTime(System.currentTimeMillis());
                saveRatingAcquired(r);
            }

            creditEngine.apply(userId, 2, "reward", "评分奖励");
        } finally {
            concurrencyGuard.releaseEsWrite();
        }

        return new RatingResult(true, existing != null ? "评分已更新" : "评分成功");
    }

    public Map<String, Object> getRatingStats(int entityType, Long entityId) {
        try {
            var resp = es.search(s -> s
                    .index(ratingIndex)
                    .query(q -> q.bool(b -> b
                        .must(m -> m.term(t -> t.field("entityType").value(entityType)))
                        .must(m -> m.term(t -> t.field("entityId").value(entityId)))
                    ))
                    .aggregations("avg_score", a -> a.avg(av -> av.field("score")))
                    .aggregations("score_dist", a -> a.terms(t -> t.field("score").size(5)))
                    .size(0),
                Void.class);

            // avg
            var avg = resp.aggregations().get("avg_score").avg();
            double avgVal = avg != null ? avg.value() : 0.0;

            // distribution
            var distAgg = resp.aggregations().get("score_dist").sterms();
            Map<Integer, Integer> dist = Map.of(1, 0, 2, 0, 3, 0, 4, 0, 5, 0);
            if (distAgg != null) {
                var mutable = new LinkedHashMap<>(dist);
                for (var bucket : distAgg.buckets().array()) {
                    int key = Integer.parseInt(bucket.key().stringValue());
                    mutable.put(key, (int) bucket.docCount());
                }
                dist = mutable;
            }

            // count via separate query or from buckets sum
            long total = dist.values().stream().mapToInt(Integer::intValue).sum();

            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("count", total);
            stats.put("avg", Math.round(avgVal * 100.0) / 100.0);
            stats.put("distribution", dist);
            return stats;
        } catch (Exception e) {
            log.error("getRatingStats failed: {}", e.getMessage());
            return Map.of("count", 0, "avg", 0.0);
        }
    }

    public RatingRecord getUserRating(int entityType, Long entityId, Long userId) {
        try {
            var resp = es.search(s -> s
                    .index(ratingIndex)
                    .query(q -> q.bool(b -> b
                        .must(m -> m.term(t -> t.field("entityType").value(entityType)))
                        .must(m -> m.term(t -> t.field("entityId").value(entityId)))
                        .must(m -> m.term(t -> t.field("userId").value(userId)))
                    ))
                    .size(1),
                RatingRecord.class);
            return resp.hits().hits().stream().map(Hit::source).findFirst().orElse(null);
        } catch (Exception e) {
            log.error("getUserRating failed: {}", e.getMessage());
            return null;
        }
    }

    private Long getLastRatingTime(Long userId) {
        try {
            var resp = es.search(s -> s
                    .index(ratingIndex)
                    .query(q -> q.term(t -> t.field("userId").value(userId)))
                    .sort(so -> so.field(f -> f.field("createTime").order(SortOrder.Desc)))
                    .size(1),
                RatingRecord.class);
            var r = resp.hits().hits().stream().map(Hit::source).findFirst().orElse(null);
            return r != null ? r.getCreateTime() : null;
        } catch (Exception e) {
            log.error("getLastRatingTime failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 已获取到 ES 写入锁后调用，直接执行写入（已持有锁，不重复 tryAcquire）。
     * 由 {@link #rate(int, Long, Long, int, String, String, String)} 在 acquire 成功后调用。
     */
    private void saveRatingAcquired(RatingRecord rating) {
        try {
            es.index(i -> i.index(ratingIndex).id(rating.getId()).document(rating));
        } catch (Exception e) {
            log.error("saveRating failed: {}", e.getMessage());
        }
    }
}
