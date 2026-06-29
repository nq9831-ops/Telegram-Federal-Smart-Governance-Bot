package com.tgf.bot.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * BotEntity — 机器人实体。
 * 记录联盟生态中注册的第三方 Telegram 机器人信息及信用评分。
 * @since 1.0
 */
@Entity
@Table(name = "bots", indexes = {
    @Index(name = "idx_bot_credit", columnList = "botCreditScore")
})
public class BotEntity {

    @Id
    private Long botId;                     // Telegram Bot ID

    @Column(length = 128)
    private String botUsername;             // @botusername

    @Column(length = 128)
    private String botName;                 // 机器人名称

    private int botCreditScore = 100;       // 机器人信用分 0-100

    private long apiCallCount;              // API调用总数
    private double avgResponseMs;           // 平均响应毫秒

    private int removedFromGroups;          // 被多群移除次数
    private int violationHitCount;          // 违规关键词命中次数

    private boolean isActive = true;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ===== getters/setters =====

    public Long getBotId() { return botId; }
    public void setBotId(Long botId) { this.botId = botId; }
    public String getBotUsername() { return botUsername; }
    public void setBotUsername(String botUsername) { this.botUsername = botUsername; }
    public String getBotName() { return botName; }
    public void setBotName(String botName) { this.botName = botName; }
    public int getBotCreditScore() { return botCreditScore; }
    public void setBotCreditScore(int botCreditScore) { this.botCreditScore = botCreditScore; }
    public long getApiCallCount() { return apiCallCount; }
    public void setApiCallCount(long apiCallCount) { this.apiCallCount = apiCallCount; }
    public double getAvgResponseMs() { return avgResponseMs; }
    public void setAvgResponseMs(double avgResponseMs) { this.avgResponseMs = avgResponseMs; }
    public int getRemovedFromGroups() { return removedFromGroups; }
    public void setRemovedFromGroups(int removedFromGroups) { this.removedFromGroups = removedFromGroups; }
    public int getViolationHitCount() { return violationHitCount; }
    public void setViolationHitCount(int violationHitCount) { this.violationHitCount = violationHitCount; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
