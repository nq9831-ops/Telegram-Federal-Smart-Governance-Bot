package com.tgf.bot.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * ViolationTemplateEntity — 违规模板实体。
 * 共享的违规模板库，当消息匹配模板时可直接判定违规，跳过 DeepSeek AI 分析。
 * @since 1.0
 */
@Entity
@Table(name = "violation_template", indexes = {
    @Index(name = "idx_template_category", columnList = "category"),
    @Index(name = "idx_template_status", columnList = "status"),
    @Index(name = "idx_template_hit", columnList = "hitCount")
})
public class ViolationTemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long templateId;

    @Column(columnDefinition = "TEXT")
    private String templateText;            // 模板文本（含变量占位符）

    @Column(length = 32)
    private String category;                // 违规类别

    private int hitCount;                   // 命中次数

    @Enumerated(EnumType.STRING)
    private TemplateStatus status = TemplateStatus.PENDING;

    private LocalDateTime firstSeenAt;      // 首次发现
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        firstSeenAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum TemplateStatus {
        PENDING, ACTIVE, DELETED
    }

    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getTemplateText() { return templateText; }
    public void setTemplateText(String templateText) { this.templateText = templateText; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public int getHitCount() { return hitCount; }
    public void setHitCount(int hitCount) { this.hitCount = hitCount; }
    public TemplateStatus getStatus() { return status; }
    public void setStatus(TemplateStatus status) { this.status = status; }
    public LocalDateTime getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(LocalDateTime firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
