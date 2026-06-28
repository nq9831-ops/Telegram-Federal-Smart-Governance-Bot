package com.tgf.bot.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * UserLeaveEntity — 退群记录实体。
 * 记录用户退出联盟群组的行为，包括主动退出和被踢出两种类型。
 * @since 1.0
 */
@Entity
@Table(name = "user_leaves", indexes = {
    @Index(name = "idx_ul_user", columnList = "userId"),
    @Index(name = "idx_ul_group", columnList = "groupId"),
    @Index(name = "idx_ul_time", columnList = "leftAt")
})
public class UserLeaveEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private Long groupId;

    private LocalDateTime leftAt;

    @Column(length = 16)
    private String leftType;                //主动退出 / 被踢出

    private int currentCreditScore;         // 退群时的信用分

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (leftAt == null) leftAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }
    public LocalDateTime getLeftAt() { return leftAt; }
    public void setLeftAt(LocalDateTime leftAt) { this.leftAt = leftAt; }
    public String getLeftType() { return leftType; }
    public void setLeftType(String leftType) { this.leftType = leftType; }
    public int getCurrentCreditScore() { return currentCreditScore; }
    public void setCurrentCreditScore(int currentCreditScore) { this.currentCreditScore = currentCreditScore; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
