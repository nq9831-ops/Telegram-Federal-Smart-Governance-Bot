package com.tgf.bot.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * RatingRuleEntity — 评分规则版本实体。
 * 记录信用评分规则的历史版本，支持规则回滚和历史追溯。
 * @since 1.0
 */
@Entity
@Table(name = "rating_rules")
public class RatingRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private int ruleVersion;                // 规则版本号 v1/v2/v3...

    @Column(length = 64)
    private String ruleKey;                 // 规则键名（如 porn_cross_penalty / ad_penalty）

    @Column(length = 32)
    private String ruleName;                // 规则名称

    private int penaltyValue;               // 扣分/奖励值

    @Column(columnDefinition = "TEXT")
    private String description;             // 变更说明

    @Column(length = 64)
    private String changedBy;               // 变更人（super_admin / auto）

    private LocalDateTime effectiveAt;      // 生效时间
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public int getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(int ruleVersion) { this.ruleVersion = ruleVersion; }
    public String getRuleKey() { return ruleKey; }
    public void setRuleKey(String ruleKey) { this.ruleKey = ruleKey; }
    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }
    public int getPenaltyValue() { return penaltyValue; }
    public void setPenaltyValue(int penaltyValue) { this.penaltyValue = penaltyValue; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getChangedBy() { return changedBy; }
    public void setChangedBy(String changedBy) { this.changedBy = changedBy; }
    public LocalDateTime getEffectiveAt() { return effectiveAt; }
    public void setEffectiveAt(LocalDateTime effectiveAt) { this.effectiveAt = effectiveAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
