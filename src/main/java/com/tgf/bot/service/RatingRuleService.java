package com.tgf.bot.service;

import com.tgf.bot.model.RatingRuleEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RatingRuleService — 评分规则版本管理服务。
 * 
 * 管理信用评分规则的版本历史，每次规则变更时版本号递增，
 * 历史扣分记录保留旧版本号，支持规则回滚。
 * @since 1.0
 */
@Service
public class RatingRuleService {

    private static final Logger log = LoggerFactory.getLogger(RatingRuleService.class);

    @PersistenceContext
    private EntityManager em;

    public int getCurrentVersion() {
        var result = em.createQuery(
            "SELECT COALESCE(MAX(r.ruleVersion), 0) FROM RatingRuleEntity r",
            Integer.class).getSingleResult();
        return result;
    }

    public RatingRuleEntity getLatestRule(String ruleKey) {
        var list = em.createQuery(
            "FROM RatingRuleEntity r WHERE r.ruleKey = :key ORDER BY r.ruleVersion DESC",
            RatingRuleEntity.class)
            .setParameter("key", ruleKey)
            .setMaxResults(1)
            .getResultList();
        return list.isEmpty() ? null : list.get(0);
    }

    public List<RatingRuleEntity> getRulesByVersion(int version) {
        return em.createQuery(
            "FROM RatingRuleEntity r WHERE r.ruleVersion = :version",
            RatingRuleEntity.class)
            .setParameter("version", version)
            .getResultList();
    }

    /** 更新规则 - 自动递增版本号 */
    @Transactional
    public int updateRule(String ruleKey, String ruleName, int newPenaltyValue,
                          String description, String changedBy) {
        var existing = getLatestRule(ruleKey);
        int currentVersion = getCurrentVersion();
        int newVersion = currentVersion + 1;

        RatingRuleEntity rule = new RatingRuleEntity();
        rule.setRuleVersion(newVersion);
        rule.setRuleKey(ruleKey);
        rule.setRuleName(ruleName);
        rule.setPenaltyValue(newPenaltyValue);
        rule.setDescription(description);
        rule.setChangedBy(changedBy);
        rule.setEffectiveAt(LocalDateTime.now());
        em.persist(rule);

        // 通知用户
        log.info("Rating rule updated: {} v{}: {} (was {} -> {}) by {}",
            ruleKey, newVersion, description,
            existing != null ? existing.getPenaltyValue() : "N/A",
            newPenaltyValue, changedBy);

        return newVersion;
    }

    public String getVersionSummary() {
        int current = getCurrentVersion();

        var recent = em.createQuery(
            "FROM RatingRuleEntity r ORDER BY r.createdAt DESC",
            RatingRuleEntity.class)
            .setMaxResults(5)
            .getResultList();

        StringBuilder sb = new StringBuilder();
        sb.append("📋 评分规则版本 v").append(current).append("\n\n");
        for (var r : recent) {
            sb.append("v").append(r.getRuleVersion()).append(" ")
                .append(r.getRuleName()).append(": ")
                .append(r.getPenaltyValue()).append("分")
                .append(" (").append(r.getDescription()).append(")")
                .append("\n");
        }
        sb.append("\n历史扣分记录保留旧版本号，不追溯变更。");

        return sb.toString();
    }

    /** 初始化默认规则 */
    @Transactional
    public void initDefaults() {
        if (getCurrentVersion() > 0) return;

        int v = 1;
        updateRule("porn_cross_penalty", "色情跨域扣分", 30, "色情跨域扣30分", "system");
        updateRule("gambling_cross_penalty", "赌博跨域扣分", 20, "赌博跨域扣20分", "system");
        updateRule("ad_penalty", "普通广告扣分", 5, "普通广告扣5-10分(视置信度)", "system");
    }
}
