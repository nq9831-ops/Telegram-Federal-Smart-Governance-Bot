package com.tgf.bot.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * GroupEntity — 群组实体。
 * 记录联盟收录的 Telegram 群组信息及环境信用评分。
 * @since 1.0
 */
@Entity
@Table(name = "groups", indexes = {
    @Index(name = "idx_group_active", columnList = "isActive"),
    @Index(name = "idx_group_label", columnList = "groupLabel"),
    @Index(name = "idx_env_score", columnList = "environmentScore")
})
public class GroupEntity {

    @Id
    private Long groupId;                       // Telegram Chat ID

    @Column(length = 128)
    private String title;                       // 群组名称

    @Column(length = 64)
    private String username;                    // 公开群组 @username

    private String description;                 // 群组描述

    private Long memberCount;                   // 成员数
    private Long adminCount;                    // 管理员数

    @Enumerated(EnumType.STRING)
    private GroupLabel groupLabel = GroupLabel.NONE;

    private int labelAuditViolations;           // 标签审计违规累计次数
    private boolean labelReapplyForbidden;      // 永久禁止申请标签

    @Column(length = 64)
    private String inviteLink;                  // 邀请链接

    private int environmentScore = 100;         // 环境信用分 0-100

    private boolean isActive = true;            // 是否活跃收录

    /** 群组级熔断状态 */
    private boolean circuitBroken = false;
    /** 熔断原因 */
    @Column(length = 128)
    private String circuitReason;
    /** 熔断开始时间 */
    private LocalDateTime circuitBrokeAt;
    /** 熔断预计恢复时间 */
    private LocalDateTime circuitRecoverAt;
    /** 全员慢速模式（消息间隔秒数，0=关闭） */
    private int slowModeSec = 0;

    private LocalDateTime invalidatedAt;
    @Column(length = 256)
    private String invalidReason;

    private LocalDateTime labelExpireAt;
    private LocalDateTime certExpireAt;

    private boolean coldStartPending;

    /** 群组白名单（JSON数组，最多10人），白名单用户不受一般违规处罚 */
    @Column(length = 512, columnDefinition = "TEXT")
    private String whitelistUserIds;          // JSON: [123,456,789]

    /** 群公告（已置顶的消息ID），管理员通过 /announce 管理 */
    private Long pinnedAnnounceMsgId;

    /** 群公告文本（最近一条公告内容缓存） */
    @Column(length = 4096)
    private String announceText;

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

    public enum GroupLabel {
        NONE, NSFW, GAMBLING
    }

    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Long getMemberCount() { return memberCount; }
    public void setMemberCount(Long memberCount) { this.memberCount = memberCount; }
    public Long getAdminCount() { return adminCount; }
    public void setAdminCount(Long adminCount) { this.adminCount = adminCount; }
    public GroupLabel getGroupLabel() { return groupLabel; }
    public void setGroupLabel(GroupLabel groupLabel) { this.groupLabel = groupLabel; }
    public int getLabelAuditViolations() { return labelAuditViolations; }
    public void setLabelAuditViolations(int labelAuditViolations) { this.labelAuditViolations = labelAuditViolations; }
    public boolean isLabelReapplyForbidden() { return labelReapplyForbidden; }
    public void setLabelReapplyForbidden(boolean labelReapplyForbidden) { this.labelReapplyForbidden = labelReapplyForbidden; }
    public String getInviteLink() { return inviteLink; }
    public void setInviteLink(String inviteLink) { this.inviteLink = inviteLink; }
    public boolean isCircuitBroken() { return circuitBroken; }
    public void setCircuitBroken(boolean circuitBroken) { this.circuitBroken = circuitBroken; }
    public String getCircuitReason() { return circuitReason; }
    public void setCircuitReason(String circuitReason) { this.circuitReason = circuitReason; }
    public LocalDateTime getCircuitBrokeAt() { return circuitBrokeAt; }
    public void setCircuitBrokeAt(LocalDateTime circuitBrokeAt) { this.circuitBrokeAt = circuitBrokeAt; }
    public LocalDateTime getCircuitRecoverAt() { return circuitRecoverAt; }
    public void setCircuitRecoverAt(LocalDateTime circuitRecoverAt) { this.circuitRecoverAt = circuitRecoverAt; }
    public int getSlowModeSec() { return slowModeSec; }
    public void setSlowModeSec(int slowModeSec) { this.slowModeSec = slowModeSec; }
    public int getEnvironmentScore() { return environmentScore; }
    public void setEnvironmentScore(int environmentScore) { this.environmentScore = environmentScore; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public LocalDateTime getInvalidatedAt() { return invalidatedAt; }
    public void setInvalidatedAt(LocalDateTime invalidatedAt) { this.invalidatedAt = invalidatedAt; }
    public String getInvalidReason() { return invalidReason; }
    public void setInvalidReason(String invalidReason) { this.invalidReason = invalidReason; }
    public LocalDateTime getLabelExpireAt() { return labelExpireAt; }
    public void setLabelExpireAt(LocalDateTime labelExpireAt) { this.labelExpireAt = labelExpireAt; }
    public LocalDateTime getCertExpireAt() { return certExpireAt; }
    public void setCertExpireAt(LocalDateTime certExpireAt) { this.certExpireAt = certExpireAt; }
    public boolean isColdStartPending() { return coldStartPending; }
    public void setColdStartPending(boolean coldStartPending) { this.coldStartPending = coldStartPending; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    /** 获取白名单用户ID列表 */
    public java.util.Set<Long> getWhitelistUserIds() {
        if (whitelistUserIds == null || whitelistUserIds.isBlank()) return java.util.Collections.emptySet();
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var arr = mapper.readTree(whitelistUserIds);
            java.util.Set<Long> ids = new java.util.HashSet<>();
            for (var node : arr) ids.add(node.asLong());
            return ids;
        } catch (Exception e) {
            return java.util.Collections.emptySet();
        }
    }

    /** 设置白名单用户ID列表 */
    public void setWhitelistUserIds(java.util.Set<Long> userIds) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            this.whitelistUserIds = mapper.writeValueAsString(userIds);
        } catch (Exception e) {
            this.whitelistUserIds = "[]";
        }
    }

    public String getWhitelistUserIdsRaw() { return whitelistUserIds; }
    public void setWhitelistUserIdsRaw(String whitelistUserIds) { this.whitelistUserIds = whitelistUserIds; }

    /** 检查用户是否在此群白名单中 */
    public boolean isWhitelisted(long userId) {
        return getWhitelistUserIds().contains(userId);
    }

    /** 添加用户到白名单（上限10人） */
    public boolean addWhitelistUser(long userId) {
        var ids = getWhitelistUserIds();
        if (ids.size() >= 10) return false;
        if (ids.contains(userId)) return true;
        ids.add(userId);
        setWhitelistUserIds(ids);
        return true;
    }

    /** 从白名单移除用户 */
    public boolean removeWhitelistUser(long userId) {
        var ids = getWhitelistUserIds();
        if (!ids.remove(userId)) return false;
        setWhitelistUserIds(ids);
        return true;
    }

    public Long getPinnedAnnounceMsgId() { return pinnedAnnounceMsgId; }
    public void setPinnedAnnounceMsgId(Long pinnedAnnounceMsgId) { this.pinnedAnnounceMsgId = pinnedAnnounceMsgId; }
    public String getAnnounceText() { return announceText; }
    public void setAnnounceText(String announceText) { this.announceText = announceText; }
}
