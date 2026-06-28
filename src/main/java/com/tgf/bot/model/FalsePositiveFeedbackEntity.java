package com.tgf.bot.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * FalsePositiveFeedbackEntity — 误报反馈实体。
 * 用于审核官对 DeepSeek AI 判定结果进行人工复核反馈，记录误判、漏判、边界情况等。
 * @since 1.0
 */
@Entity
@Table(name = "false_positive_feedback", indexes = {
    @Index(name = "idx_fp_category", columnList = "feedbackCategory"),
    @Index(name = "idx_fp_week", columnList = "reportWeek")
})
public class FalsePositiveFeedbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long ticketId;                  // 关联工单ID
    private Long reviewerId;                // 审核官ID

    @Column(length = 32)
    private String feedbackCategory;        // 误判/漏判/边界

    @Column(length = 64)
    private String misclassifyType;         // 正常被标色情/色情被标赌博/违规未识别等

    @Column(columnDefinition = "TEXT")
    private String originalText;            // 原消息文本

    @Column(length = 32)
    private String deepseekCategory;        // DeepSeek判定类别

    private double deepseekConfidence;      // DeepSeek置信度

    @Column(length = 512)
    private String comment;                 // 审核官备注

    private int reportWeek;                 // 周数 (用于周报聚合)

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); }

    /** 获取反馈记录ID */
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }
    public Long getReviewerId() { return reviewerId; }
    public void setReviewerId(Long reviewerId) { this.reviewerId = reviewerId; }
    public String getFeedbackCategory() { return feedbackCategory; }
    public void setFeedbackCategory(String feedbackCategory) { this.feedbackCategory = feedbackCategory; }
    public String getMisclassifyType() { return misclassifyType; }
    public void setMisclassifyType(String misclassifyType) { this.misclassifyType = misclassifyType; }
    public String getOriginalText() { return originalText; }
    public void setOriginalText(String originalText) { this.originalText = originalText; }
    public String getDeepseekCategory() { return deepseekCategory; }
    public void setDeepseekCategory(String deepseekCategory) { this.deepseekCategory = deepseekCategory; }
    public double getDeepseekConfidence() { return deepseekConfidence; }
    public void setDeepseekConfidence(double deepseekConfidence) { this.deepseekConfidence = deepseekConfidence; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public int getReportWeek() { return reportWeek; }
    public void setReportWeek(int reportWeek) { this.reportWeek = reportWeek; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
