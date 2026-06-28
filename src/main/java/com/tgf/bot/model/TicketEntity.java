package com.tgf.bot.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * TicketEntity — 审核工单实体。
 * 处理联盟中各类审核与申请流程，支持死罪复核、标签申请、广告商认证、普通申诉等。
 * @since 1.0
 */
@Entity
@Table(name = "tickets", indexes = {
    @Index(name = "idx_ticket_type", columnList = "ticketType"),
    @Index(name = "idx_ticket_status", columnList = "status"),
    @Index(name = "idx_ticket_priority", columnList = "priority"),
    @Index(name = "idx_ticket_deadline", columnList = "deadlineAt")
})
public class TicketEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long ticketId;

    @Column(length = 32)
    private String ticketType;              // death_review / label_apply / ad_apply / appeal / contact_admin

    @Column(length = 16)
    private String status = "PENDING";      // PENDING / PROCESSING / PASSED / REJECTED / ESCALATED

    private int priority;                   // 0=normal 1=high 2=urgent

    private Long submitterId;               // 提交者 User ID
    private Long targetUserId;              // 目标用户（举报/死刑对象）

    @Column(columnDefinition = "TEXT")
    private String content;                 // 内容/理由

    @Column(length = 64)
    private String deepseekAnalysisId;      // 关联的DeepSeek分析

    private Long reviewerId;                // 当前处理人
    private LocalDateTime reviewedAt;
    @Column(length = 512)
    private String reviewComment;

    private LocalDateTime deadlineAt;       // SLA截止
    private LocalDateTime escalatedAt;      // 升级时间
    private int escalationLevel;            // 1/2/3

    private boolean coldStart;

    private Long relatedGroupId;

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

    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }
    public String getTicketType() { return ticketType; }
    public void setTicketType(String ticketType) { this.ticketType = ticketType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public Long getSubmitterId() { return submitterId; }
    public void setSubmitterId(Long submitterId) { this.submitterId = submitterId; }
    public Long getTargetUserId() { return targetUserId; }
    public void setTargetUserId(Long targetUserId) { this.targetUserId = targetUserId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getDeepseekAnalysisId() { return deepseekAnalysisId; }
    public void setDeepseekAnalysisId(String deepseekAnalysisId) { this.deepseekAnalysisId = deepseekAnalysisId; }
    public Long getReviewerId() { return reviewerId; }
    public void setReviewerId(Long reviewerId) { this.reviewerId = reviewerId; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getReviewComment() { return reviewComment; }
    public void setReviewComment(String reviewComment) { this.reviewComment = reviewComment; }
    public LocalDateTime getDeadlineAt() { return deadlineAt; }
    public void setDeadlineAt(LocalDateTime deadlineAt) { this.deadlineAt = deadlineAt; }
    public LocalDateTime getEscalatedAt() { return escalatedAt; }
    public void setEscalatedAt(LocalDateTime escalatedAt) { this.escalatedAt = escalatedAt; }
    public int getEscalationLevel() { return escalationLevel; }
    public void setEscalationLevel(int escalationLevel) { this.escalationLevel = escalationLevel; }
    public boolean isColdStart() { return coldStart; }
    public void setColdStart(boolean coldStart) { this.coldStart = coldStart; }
    public Long getRelatedGroupId() { return relatedGroupId; }
    public void setRelatedGroupId(Long relatedGroupId) { this.relatedGroupId = relatedGroupId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
