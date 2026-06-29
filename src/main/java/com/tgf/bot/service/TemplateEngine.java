package com.tgf.bot.service;

import com.tgf.bot.model.ViolationTemplateEntity;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * TemplateEngine — 违规模板匹配引擎。
 *
 * 当消息匹配活跃模板（相似度 ≥ 80%）时可直接判定违规，
 * 跳过 {@link ContentModerationService} 的 DeepSeek API 调用，节省约 100 tokens/条。
 *
 * <p>匹配流程：</p>
 * <ol>
 *   <li>正则匹配 — 快速筛选候选模板（支持 {link}/{amount}/{username} 占位符）</li>
 *   <li>骨架提取 — 去除变量保留固定结构</li>
 *   <li>相似度计算 — Levenshtein 编辑距离，≥80% 命中</li>
 * </ol>
 *
 * <p>命中计数：使用 {@link java.util.concurrent.atomic.AtomicInteger} 内存计数，
 * 由 {@link BotScheduler#refreshTemplateCache()} 每 10 分钟批量落库。</p>
 *
 * <p>依赖：</p>
 * <ul>
 *   <li>{@link EntityManager} — 模板数据读取和命中计数批量写回</li>
 *   <li>{@link ViolationTemplateEntity} — 模板实体</li>
 * </ul>
 *
 * <p>被引用：</p>
 * <ul>
 *   <li>{@link GroupHandler} — 群组消息审核时调用 match()</li>
 *   <li>{@link BotScheduler} — 定期刷新缓存并批量写回命中计数</li>
 * </ul>
 *
 * @since 1.0
 */
@Service
public class TemplateEngine {

    private static final Logger log = LoggerFactory.getLogger(TemplateEngine.class);

    @PersistenceContext
    private EntityManager em;

    @PostConstruct
    public void init() {
        refreshCache();
        log.info("TemplateEngine initialized: {} active templates loaded", activeTemplates.size());
    }

    /** 缓存活跃模板 — CopyOnWriteArrayList 保证读不阻塞 */
    private volatile List<TemplateEntry> activeTemplates = List.of();

    public record TemplateEntry(Long id, String text, String category, Pattern regexPattern, java.util.concurrent.atomic.AtomicInteger hitCount) {}

    public void refreshCache() {
        // 批量写回上一轮的命中计数到 DB
        for (var entry : activeTemplates) {
            int hits = entry.hitCount().getAndSet(0);
            if (hits > 0) {
                em.createQuery("UPDATE ViolationTemplateEntity SET hitCount = hitCount + :hits WHERE templateId = :id")
                    .setParameter("hits", hits)
                    .setParameter("id", entry.id())
                    .executeUpdate();
            }
        }

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
                    Pattern.compile(regex, Pattern.CASE_INSENSITIVE), new java.util.concurrent.atomic.AtomicInteger(0));
            })
            .toList();
    }

    public ViolationTemplateEntity match(String text) {
        if (text == null || text.isBlank()) return null;

        String skeleton = null;
        for (var entry : activeTemplates) {
            if (!entry.regexPattern().matcher(text).find()) continue;
            // 延迟计算骨架（只在 regex 命中时才计算）
            if (skeleton == null) {
                skeleton = extractSkeleton(text);
                // 消息过短直接放弃相似度判定
                if (skeleton.length() < 10) break;
            }
            // 长度差超过 50% 直接跳过，避免 O(n²) 编辑距离浪费 CPU
            int eLen = entry.text().length();
            int tLen = skeleton.length();
            if (Math.abs(eLen - tLen) > Math.max(eLen, tLen) * 0.5) continue;
            double sim = similarity(text, entry.text());
            if (sim >= 0.80) {
                // 内存计数，由 refreshCache() 批量落库
                entry.hitCount().incrementAndGet();
                return em.find(ViolationTemplateEntity.class, entry.id());
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
