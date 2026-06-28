package com.tgf.bot.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * AuditLogEntity — 审计日志实体。
 * 记录系统中所有重要操作轨迹，包括信用分变更、处罚、赦免、认证等行为。
 * @since 1.0
 */
@Entity
@Table(name = "audit_log", indexes = {
    @Index(name = "idx_audit_operator", columnList = "operatorType"),
    @Index(name = "idx_audit_action", columnList = "actionType"),
    @Index(name = "idx_audit_user", columnList = "targetUserId"),
    @Index(name = "idx_audit_time", columnList = "createdAt")
})
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 16)
    private String operatorType;            // system / admin / user / deepseek

    private Long operatorUserId;            // 操作者ID

    @Column(length = 32)
    private String actionType;              // credit_change / punish / pardon / certify

    private Long targetUserId;              // 目标用户
    private Long targetGroupId;             // 目标群组

    private int beforeValue;                // 变更前数值
    private int afterValue;                 // 变更后数值

    @Column(length = 1024)
    private String reason;                  // 原因

    @Column(length = 64)
    private String deepseekAnalysisId;      // DeepSeek分析ID

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    /** 获取审计日志ID */
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOperatorType() { return operatorType; }
    public void setOperatorType(String operatorType) { this.operatorType = operatorType; }
    public Long getOperatorUserId() { return operatorUserId; }
    public void setOperatorUserId(Long operatorUserId) { this.operatorUserId = operatorUserId; }
    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }
    public Long getTargetUserId() { return targetUserId; }
    public void setTargetUserId(Long targetUserId) { this.targetUserId = targetUserId; }
    public Long getTargetGroupId() { return targetGroupId; }
    public void setTargetGroupId(Long targetGroupId) { this.targetGroupId = targetGroupId; }
    public int getBeforeValue() { return beforeValue; }
    public void setBeforeValue(int beforeValue) { this.beforeValue = beforeValue; }
    public int getAfterValue() { return afterValue; }
    public void setAfterValue(int afterValue) { this.afterValue = afterValue; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getDeepseekAnalysisId() { return deepseekAnalysisId; }
    public void setDeepseekAnalysisId(String deepseekAnalysisId) { this.deepseekAnalysisId = deepseekAnalysisId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
