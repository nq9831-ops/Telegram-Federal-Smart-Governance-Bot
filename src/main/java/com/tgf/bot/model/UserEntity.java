package com.tgf.bot.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * UserEntity — 用户实体。
 * 联盟用户的核心实体，记录用户的信用分、风险等级、认证状态等关键信息。
 * @since 1.0
 */
@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_credit_score", columnList = "creditScore"),
    @Index(name = "idx_device_fingerprint", columnList = "deviceFingerprintHash"),
    @Index(name = "idx_username", columnList = "username")
})
public class UserEntity {

    @Id
    private Long userId;                    // Telegram User ID

    @Version
    private Long version;                   // JPA 乐观锁版本号

    @Column(length = 64)
    private String username;                // 当前用户名

    private int creditScore = 100;          // 信用分 0-100

    @Enumerated(EnumType.STRING)
    private RiskLevel deepseekRiskLevel = RiskLevel.SAFE;

    @Column(length = 128)
    private String deviceFingerprintHash;   // 设备指纹哈希

    private boolean isCertifiedAdvertiser;  // 是否认证广告商
    private LocalDateTime certExpireAt;     // 认证到期

    private boolean isFrozen;               // 信用分是否冻结
    private LocalDateTime frozenUntil;      // 冻结截止

    private boolean isGroupJumper;          // 是否标记为群组跳蚤

    private int dailyAdCount;               // 今日广告数
    private LocalDateTime dailyResetAt;     // 每日重置时间

    @Column(length = 32)
    private String inviteCode;              // 用户的专属邀请码
    private int inviteCount;                // 邀请次数

    @Column(length = 8)
    private String lang = "zh";             // 语言偏好

    private boolean privacyAccepted;
    private LocalDateTime privacyAcceptedAt;

    private Boolean isUnderage;             // null=未知, true=未成年, false=成年

    private boolean optOutBroadcast;

    private int profileCompleteness = 0;    // 0-5

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 是否为高级审核官（信任加权） */
    private boolean isReviewer = false;
    /** 种子用户（管理员保护，不受自动禁言影响） */
    private boolean trustedSeed = false;
    /** 被谁邀请来的 */
    private Long invitedBy;
    /** 注册时的 IP 地址 */
    @Column(length = 45)
    private String registrationIp;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 用户角色（权限分级）。
     * <ul>
     *   <li>USER — 普通用户，仅可查看自己信息</li>
     *   <li>REVIEWER — 审核员，可查看和处理审核工单</li>
     *   <li>MODERATOR — 版主，可管理群组/处罚/下架</li>
     *   <li>ADMIN — 超级管理员，全部权限</li>
     * </ul>
     */
    public enum RiskLevel {
        SAFE, WATCH, DANGER, DEATH
    }

    // ===== getters/setters =====

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public int getCreditScore() { return creditScore; }
    public void setCreditScore(int creditScore) { this.creditScore = creditScore; }
    public RiskLevel getDeepseekRiskLevel() { return deepseekRiskLevel; }
    public void setDeepseekRiskLevel(RiskLevel deepseekRiskLevel) { this.deepseekRiskLevel = deepseekRiskLevel; }
    public String getDeviceFingerprintHash() { return deviceFingerprintHash; }
    public void setDeviceFingerprintHash(String deviceFingerprintHash) { this.deviceFingerprintHash = deviceFingerprintHash; }
    public boolean isCertifiedAdvertiser() { return isCertifiedAdvertiser; }
    public void setCertifiedAdvertiser(boolean certifiedAdvertiser) { isCertifiedAdvertiser = certifiedAdvertiser; }
    public LocalDateTime getCertExpireAt() { return certExpireAt; }
    public void setCertExpireAt(LocalDateTime certExpireAt) { this.certExpireAt = certExpireAt; }
    public boolean isFrozen() { return isFrozen; }
    public void setFrozen(boolean frozen) { isFrozen = frozen; }
    public LocalDateTime getFrozenUntil() { return frozenUntil; }
    public void setFrozenUntil(LocalDateTime frozenUntil) { this.frozenUntil = frozenUntil; }
    public boolean isGroupJumper() { return isGroupJumper; }
    public void setGroupJumper(boolean groupJumper) { isGroupJumper = groupJumper; }
    public int getDailyAdCount() { return dailyAdCount; }
    public void setDailyAdCount(int dailyAdCount) { this.dailyAdCount = dailyAdCount; }
    public LocalDateTime getDailyResetAt() { return dailyResetAt; }
    public void setDailyResetAt(LocalDateTime dailyResetAt) { this.dailyResetAt = dailyResetAt; }
    public String getInviteCode() { return inviteCode; }
    public void setInviteCode(String inviteCode) { this.inviteCode = inviteCode; }
    public int getInviteCount() { return inviteCount; }
    public void setInviteCount(int inviteCount) { this.inviteCount = inviteCount; }
    public String getLang() { return lang; }
    public void setLang(String lang) { this.lang = lang; }
    public boolean isPrivacyAccepted() { return privacyAccepted; }
    public void setPrivacyAccepted(boolean privacyAccepted) { this.privacyAccepted = privacyAccepted; }
    public LocalDateTime getPrivacyAcceptedAt() { return privacyAcceptedAt; }
    public void setPrivacyAcceptedAt(LocalDateTime privacyAcceptedAt) { this.privacyAcceptedAt = privacyAcceptedAt; }
    public Boolean getIsUnderage() { return isUnderage; }
    public void setIsUnderage(Boolean isUnderage) { this.isUnderage = isUnderage; }
    public boolean isOptOutBroadcast() { return optOutBroadcast; }
    public void setOptOutBroadcast(boolean optOutBroadcast) { this.optOutBroadcast = optOutBroadcast; }
    public int getProfileCompleteness() { return profileCompleteness; }
    public void setProfileCompleteness(int profileCompleteness) { this.profileCompleteness = profileCompleteness; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public boolean getIsReviewer() { return isReviewer; }
    public void setIsReviewer(boolean reviewer) { isReviewer = reviewer; }
    public boolean getTrustedSeed() { return trustedSeed; }
    public void setTrustedSeed(boolean trustedSeed) { this.trustedSeed = trustedSeed; }
    public Long getInvitedBy() { return invitedBy; }
    public void setInvitedBy(Long invitedBy) { this.invitedBy = invitedBy; }
    public String getRegistrationIp() { return registrationIp; }
    public void setRegistrationIp(String registrationIp) { this.registrationIp = registrationIp; }
}
