package com.tgf.bot.service;

import com.tgf.bot.model.ViolationTemplateEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * TemplateEngine — 违规模板匹配引擎。
 * 
 * 当消息匹配活跃模板（相似度 ≥ 80%）时可直接判定违规，
 * 跳过 DeepSeek API 调用，节省约 100 tokens/条。
 * @since 1.0
 */
@Service
public class TemplateEngine {

    @PersistenceContext
    private EntityManager em;

    /** 缓存活跃模板 — CopyOnWriteArrayList 保证读不阻塞 */
    private volatile List<TemplateEntry> activeTemplates = List.of();

    public record TemplateEntry(Long id, String text, String category, Pattern regexPattern) {}

    public void refreshCache() {
        var query = em.createQuery(
            "FROM ViolationTemplateEntity WHERE status = 'ACTIVE'",
            ViolationTemplateEntity.class);
        var templates = query.getResultList();

        activeTemplates = templates.stream()
            .map(t -> {
                // 将模板文本转为正则
                String regex = Pattern.quote(t.getTemplateText())
                    .replace("\\{link\\}", "https?://[\\w./?=&#-]+")
                    .replace("\\{amount\\}", "\\d+(\\.\\d+)?")
                    .replace("\\{username\\}", "@?\\w+");
                return new TemplateEntry(t.getTemplateId(), t.getTemplateText(), t.getCategory(),
                    Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
            })
            .toList();
    }

    public ViolationTemplateEntity match(String text) {
        if (text == null || text.isBlank()) return null;

        for (var entry : activeTemplates) {
            if (entry.regexPattern().matcher(text).find()) {
                // 计算相似度（简化版：模板文本长度匹配率）
                double sim = similarity(text, entry.text());
                if (sim >= 0.80) {
                    // 命中计数
                    em.createQuery(
                        "UPDATE ViolationTemplateEntity SET hitCount = hitCount + 1 WHERE templateId = :id")
                        .setParameter("id", entry.id())
                        .executeUpdate();
                    return em.find(ViolationTemplateEntity.class, entry.id());
                }
            }
        }
        return null;
    }

    /**
     * 内容骨架提取：去除变量保留固定结构
     */
    public String extractSkeleton(String text) {
        return text
            .replaceAll("https?://[\\w./?=&#-]+", "{link}")
            .replaceAll("\\d+(\\.\\d+)?", "{amount}")
            .replaceAll("@?\\w{4,}", "{username}")
            .trim();
    }

    /**
     * 字符串相似度（编辑距离）
     */
    public double similarity(String a, String b) {
        if (a == null || b == null) return 0.0;
        if (a.equalsIgnoreCase(b)) return 1.0;

        String aSkeleton = extractSkeleton(a);
        String bSkeleton = extractSkeleton(b);

        // 基于骨架比较
        if (aSkeleton.length() < 10 || bSkeleton.length() < 10) return 0.0;

        int dist = levenshtein(aSkeleton.toLowerCase(), bSkeleton.toLowerCase());
        int maxLen = Math.max(aSkeleton.length(), bSkeleton.length());
        return 1.0 - (double) dist / maxLen;
    }

    private int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }
}
