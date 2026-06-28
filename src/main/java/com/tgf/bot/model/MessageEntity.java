package com.tgf.bot.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * MessageEntity — 消息记录实体。
 * 记录群组中经过 DeepSeek 分析的消息内容及判定结果。
 * @since 1.0
 */
@Entity
@Table(name = "messages", indexes = {
    @Index(name = "idx_msg_user", columnList = "userId"),
    @Index(name = "idx_msg_group", columnList = "groupId"),
    @Index(name = "idx_msg_time", columnList = "createdAt"),
    @Index(name = "idx_msg_fingerprint", columnList = "contentFingerprint")
})
public class MessageEntity {

    @Id
    private Long messageId;                 // Telegram Message ID

    private Long groupId;                   // 群组ID
    private Long userId;                    // 发送者ID

    @Column(columnDefinition = "TEXT")
    private String text;                    // 消息原文

    @Column(length = 64)
    private String contentFingerprint;      // 内容指纹哈希（用于去重）

    @Column(length = 32)
    private String category;                // 分类结果
    private double confidence;              // 置信度
    @Column(length = 512)
    private String briefReason;             // 判断理由

    private boolean isDeleted;              // 是否已删除（90天后）

    private LocalDateTime createdAt;

    public MessageEntity() {}

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    /** 获取消息ID */
    public Long getMessageId() { return messageId; }
    public void setMessageId(Long messageId) { this.messageId = messageId; }
    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getContentFingerprint() { return contentFingerprint; }
    public void setContentFingerprint(String contentFingerprint) { this.contentFingerprint = contentFingerprint; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public String getBriefReason() { return briefReason; }
    public void setBriefReason(String briefReason) { this.briefReason = briefReason; }
    public boolean isDeleted() { return isDeleted; }
    public void setDeleted(boolean deleted) { isDeleted = deleted; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
