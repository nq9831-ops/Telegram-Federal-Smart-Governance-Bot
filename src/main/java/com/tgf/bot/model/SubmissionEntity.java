package com.tgf.bot.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * SubmissionEntity — 收录提交实体。
 * 用户自行提交群组/机器人/代理信息申请纳入联盟索引。
 * @since 1.0
 */
@Entity
@Table(name = "submissions", indexes = {
    @Index(name = "idx_sub_status", columnList = "status"),
    @Index(name = "idx_sub_submitter", columnList = "submitterId"),
    @Index(name = "idx_sub_target_type", columnList = "targetType")
})
public class SubmissionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 16)
    private String targetType;              // group / bot / proxy

    // 通用信息
    @Column(length = 256)
    private String targetId;                // 目标标识（群组ID/机器人ID/代理入口）

    @Column(length = 128)
    private String title;                   // 名称

    @Column(columnDefinition = "TEXT")
    private String description;             // 描述/备注

    @Column(length = 64)
    private String contact;                 // 联系方式

    // 群组专用
    @Column(length = 256)
    private String inviteLink;              // 邀请链接

    @Column(length = 16)
    private String groupLabel;              // 群组标签

    // 代理专用
    @Column(length = 16)
    private String protocol;                // 代理协议

    @Column(length = 256)
    private String endpoint;                // 代理地址

    // 提交人
    private Long submitterId;
    private String submitterUsername;

    // 审核状态
    @Column(length = 16)
    private String status = "PENDING";      // PENDING / APPROVED / REJECTED

    private Long reviewerId;
    private LocalDateTime reviewedAt;
    @Column(length = 512)
    private String reviewComment;

    // 关联工单（审核流程）
    private Long ticketId;

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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getContact() { return contact; }
    public void setContact(String contact) { this.contact = contact; }
    public String getInviteLink() { return inviteLink; }
    public void setInviteLink(String inviteLink) { this.inviteLink = inviteLink; }
    public String getGroupLabel() { return groupLabel; }
    public void setGroupLabel(String groupLabel) { this.groupLabel = groupLabel; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public Long getSubmitterId() { return submitterId; }
    public void setSubmitterId(Long submitterId) { this.submitterId = submitterId; }
    public String getSubmitterUsername() { return submitterUsername; }
    public void setSubmitterUsername(String submitterUsername) { this.submitterUsername = submitterUsername; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getReviewerId() { return reviewerId; }
    public void setReviewerId(Long reviewerId) { this.reviewerId = reviewerId; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getReviewComment() { return reviewComment; }
    public void setReviewComment(String reviewComment) { this.reviewComment = reviewComment; }
    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
