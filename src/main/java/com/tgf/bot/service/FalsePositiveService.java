package com.tgf.bot.service;

import com.tgf.bot.model.FalsePositiveFeedbackEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.*;

/**
 * FalsePositiveService — 误报反馈闭环服务。
 * 
 * 审核官反馈 DeepSeek 误判/漏判，生成周报与优化建议，
 * 持续改进 AI 判定准确率。
 * @since 1.0
 */
@Service
public class FalsePositiveService {

    private static final Logger log = LoggerFactory.getLogger(FalsePositiveService.class);

    @PersistenceContext
    private EntityManager em;

    public FalsePositiveService() {}

    /** 提交误报反馈 */
    @Transactional
    public FalsePositiveFeedbackEntity submitFeedback(Long ticketId, Long reviewerId,
                                                       String feedbackCategory,
                                                       String misclassifyType,
                                                       String originalText,
                                                       String deepseekCategory,
                                                       double deepseekConfidence,
                                                       String comment) {
        var feedback = new FalsePositiveFeedbackEntity();
        feedback.setTicketId(ticketId);
        feedback.setReviewerId(reviewerId);
        feedback.setFeedbackCategory(feedbackCategory);
        feedback.setMisclassifyType(misclassifyType);
        feedback.setOriginalText(originalText != null ? originalText.substring(0, Math.min(1000, originalText.length())) : "");
        feedback.setDeepseekCategory(deepseekCategory);
        feedback.setDeepseekConfidence(deepseekConfidence);
        feedback.setComment(comment);
        feedback.setReportWeek(LocalDate.now().get(WeekFields.ISO.weekOfWeekBasedYear()));

        em.persist(feedback);
        log.info("FP feedback submitted: ticket={} category={} type={}",
            ticketId, feedbackCategory, misclassifyType);
        return feedback;
    }

    public String generateWeeklyReport() {
        int currentWeek = LocalDate.now().get(WeekFields.ISO.weekOfWeekBasedYear());
        return generateWeeklyReport(currentWeek);
    }

    public String generateWeeklyReport(int week) {
        // 统计各误报类型数量
        List<Object[]> stats = em.createQuery(
            "SELECT f.misclassifyType, COUNT(f) FROM FalsePositiveFeedbackEntity f " +
            "WHERE f.reportWeek = :week GROUP BY f.misclassifyType ORDER BY COUNT(f) DESC",
            Object[].class)
            .setParameter("week", week)
            .getResultList();

        long total = stats.stream().mapToLong(s -> (Long) s[1]).sum();

        // 聚合高频误报关键词模式
        List<String> recentTexts = em.createQuery(
            "SELECT f.originalText FROM FalsePositiveFeedbackEntity f " +
            "WHERE f.reportWeek = :week ORDER BY f.createdAt DESC",
            String.class)
            .setParameter("week", week)
            .setMaxResults(50)
            .getResultList();

        // 生成报告文本
        StringBuilder report = new StringBuilder();
        report.append("📊 DeepSeek误报分析周报（第").append(week).append("周）\n\n");
        report.append("总反馈数：").append(total).append("\n\n");

        if (total > 0) {
            report.append("📈 误报分布：\n");
            for (var s : stats) {
                String type = (String) s[0];
                long count = (Long) s[1];
                double pct = Math.round(count * 10000.0 / total) / 100.0;
                report.append("  · ").append(type).append(": ").append(count)
                    .append(" (").append(pct).append("%)\n");
            }

            // 找出高频误报
            for (var s : stats) {
                long count = (Long) s[1];
                if (count >= 10) {
                    report.append("\n⚠️ 高频误报警告：").append(s[0])
                        .append("（周").append(count).append("次 ≥ 10次阈值）\n");
                    report.append("  建议：调整DeepSeek提示词 或 调整置信度阈值 或 增加规则引擎例外\n");
                }
            }
        } else {
            report.append("本周暂无误报反馈。\n");
        }

        report.append("\n📝 高频关键词/模式：\n");
        // 统计关键词频率（简化版）
        Map<String, Integer> keywordFreq = new HashMap<>();
        for (String t : recentTexts) {
            if (t == null) continue;
            for (String word : t.split("[\\s,，。.!！?？]")) {
                if (word.length() >= 2) {
                    keywordFreq.merge(word, 1, Integer::sum);
                }
            }
        }
        keywordFreq.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(10)
            .forEach(e -> report.append("  · \"").append(e.getKey()).append("\": ").append(e.getValue()).append("次\n"));

        report.append("\n📋 优化建议：\n");
        report.append("1. 高频误报类型(周≥10次)考虑调整DeepSeek提示词\n");
        report.append("2. 考虑在规则引擎中增加例外规则\n");
        report.append("3. 误报率周环比趋势需持续监控\n");

        return report.toString();
    }

    public String getTrendReport() {
        int currentWeek = LocalDate.now().get(WeekFields.ISO.weekOfWeekBasedYear());
        StringBuilder sb = new StringBuilder("📈 误报率趋势（近4周）：\n");

        for (int w = currentWeek - 3; w <= currentWeek; w++) {
            var count = em.createQuery(
                "SELECT COUNT(f) FROM FalsePositiveFeedbackEntity f WHERE f.reportWeek = :week",
                Long.class).setParameter("week", w).getSingleResult();
            sb.append("  第").append(w).append("周: ").append(count).append("条\n");
        }

        return sb.toString();
    }

    public String getOptimizationSuggestions() {
        int currentWeek = LocalDate.now().get(WeekFields.ISO.weekOfWeekBasedYear());

        // 找出本周最常见的误报类型
        List<Object[]> topMisclassify = em.createQuery(
            "SELECT f.misclassifyType, COUNT(f) FROM FalsePositiveFeedbackEntity f " +
            "WHERE f.reportWeek = :week GROUP BY f.misclassifyType ORDER BY COUNT(f) DESC",
            Object[].class)
            .setParameter("week", currentWeek)
            .setMaxResults(3)
            .getResultList();

        if (topMisclassify.isEmpty()) {
            return "本周无高频误报，当前配置表现良好。";
        }

        StringBuilder sb = new StringBuilder("🔧 优化建议：\n");
        for (var item : topMisclassify) {
            String type = (String) item[0];
            long count = (Long) item[1];
            sb.append("\n").append(type).append(" (").append(count).append("次)：\n");

            // 根据类型给出建议
            switch (type) {
                case "正常内容被误标为色情" -> sb.append("  → 调整提示词：在分类规则中增加\"正常社交内容\"排除清单\n")
                    .append("  → 考虑降低色情检测的敏感度\n");
                case "正常内容被误标为广告" -> sb.append("  → 调整提示词：区分\"信息分享\"和\"商业推广\"\n")
                    .append("  → 增加白名单链接域名\n");
                case "正常内容被误标为赌博" -> sb.append("  → 调整提示词：排除\"玩法/攻略/讨论\"等非博彩场景\n")
                    .append("  → 增加上下文关联判断\n");
                default -> sb.append("  → 建议审查相关消息，调整提示词\n");
            }
        }
        return sb.toString();
    }
}
