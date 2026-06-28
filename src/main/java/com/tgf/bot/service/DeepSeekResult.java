package com.tgf.bot.service;

/**
 * DeepSeekResult — AI 分类结果 DTO。
 * 
 * DeepSeek 内容分类判定的结果封装，包含类别、置信度、
 * 关键词及判断理由，提供便捷的违规判定辅助方法。
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
