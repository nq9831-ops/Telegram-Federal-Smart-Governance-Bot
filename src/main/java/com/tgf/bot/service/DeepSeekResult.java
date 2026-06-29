package com.tgf.bot.service;

/**
 * DeepSeekResult — AI 分类结果 DTO。
 *
 * {@link ContentModerationService} 的 DeepSeek 内容分类判定的结果封装，
 * 包含类别、置信度、关键词及判断理由，提供便捷的违规判定辅助方法。
 *
 * <p>类别枚举：</p>
 * <ul>
 *   <li>正常 — 无违规</li>
 *   <li>诈骗 — 死刑级别，零容忍</li>
 *   <li>政治/煽动/分裂 — 死刑级别，需人工复核</li>
 *   <li>色情 — 安全港豁免（NSFW 群组），跨域扣 30 分</li>
 *   <li>赌博 — 安全港豁免（GAMBLING 群组），跨域扣 20 分</li>
 *   <li>普通广告 — 认证广告商豁免（有配额），普通用户扣 5-10 分</li>
 * </ul>
 *
 * <p>被引用：</p>
 * <ul>
 *   <li>{@link GroupHandler} — 消息审核时使用判定结果</li>
 *   <li>{@link PenaltyEngine} — 根据类别执行处罚</li>
 *   <li>{@link ContentModerationService} — 生成分类结果</li>
 * </ul>
 *
 * @since 1.0
 */
public class DeepSeekResult {

    private String category;            // 正常/诈骗/政治/煽动/分裂/色情/赌博/普通广告
    private double confidence;          // 置信度 0-1
    private String[] keywords;          // 命中关键词
    private String briefReason;         // 判断理由

    public DeepSeekResult() {}

    public DeepSeekResult(String category, double confidence, String[] keywords, String briefReason) {
        this.category = category;
        this.confidence = confidence;
        this.keywords = keywords;
        this.briefReason = briefReason;
    }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public String[] getKeywords() { return keywords; }
    public void setKeywords(String[] keywords) { this.keywords = keywords; }
    public String getBriefReason() { return briefReason; }
    public void setBriefReason(String briefReason) { this.briefReason = briefReason; }

    public boolean isViolation() {
        return !"正常".equals(category);
    }

    public boolean isAutoExecutable() {
        return confidence >= 0.85;
    }

    public boolean needsHumanReview() {
        return confidence >= 0.60 && confidence < 0.85;
    }

    // === 5大类违规 ===
    public boolean isScam() { return "诈骗".equals(category); }
    public boolean isPolitical() { return "政治".equals(category) || "煽动".equals(category) || "分裂".equals(category); }
    public boolean isPorn() { return "色情".equals(category); }
    public boolean isGambling() { return "赌博".equals(category); }
    public boolean isAd() { return "普通广告".equals(category); }

    public boolean isDeathPenalty() {
        return isScam() || isPolitical();
    }
}
