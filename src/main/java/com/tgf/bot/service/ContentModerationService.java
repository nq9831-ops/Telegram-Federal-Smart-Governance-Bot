package com.tgf.bot.service;

import com.tgf.bot.config.ConcurrencyGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * ContentModerationService — 统一内容审核服务。
 * <p>
 * 支持云端 DeepSeek API 与本地 Ollama 两种方式，通过 {@code moderation.provider} 配置切换。
 * 数据不外出时设置 {@code moderation.provider=local} 并部署本地 Ollama 服务即可。
 * </p>
 *
 * <pre>{@code
 * # 云端模式（默认）
 * moderation.provider=cloud
 * deepseek.api-key=sk-xxx
 *
 * # 本地模式（数据不外出）
 * moderation.provider=local
 * moderation.local.api-url=http://localhost:11434/v1/chat/completions
 * moderation.local.model=qwen2.5:7b
 * }</pre>
 *
 * @since 1.0
 */
@Service
public class ContentModerationService {

    private static final Logger log = LoggerFactory.getLogger(ContentModerationService.class);

    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private static final int MAX_RETRIES = 2;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    private final HttpClient client;
    private final String provider;
    private final String apiUrl;
    private final String model;
    private final String apiKey;
    private final int timeoutMs;
    private final ConcurrencyGuard concurrencyGuard;

    public ContentModerationService(
            @Value("${moderation.provider:cloud}") String provider,
            @Value("${deepseek.api-url:https://api.deepseek.com/v1/chat/completions}") String cloudApiUrl,
            @Value("${deepseek.model:deepseek-chat}") String cloudModel,
            @Value("${deepseek.api-key:}") String apiKey,
            @Value("${moderation.local.api-url:http://localhost:11434/v1/chat/completions}") String localApiUrl,
            @Value("${moderation.local.model:qwen2.5:7b}") String localModel,
            @Value("${deepseek.timeout-ms:30000}") int timeoutMs,
            ConcurrencyGuard concurrencyGuard) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.timeoutMs = timeoutMs;
        this.concurrencyGuard = concurrencyGuard;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        if ("local".equals(provider)) {
            this.apiUrl = localApiUrl;
            this.model = localModel;
            log.info("ContentModeration: provider=local, model={}, url={}", localModel, localApiUrl);
        } else {
            if (apiKey == null || apiKey.isBlank()) {
                log.warn("ContentModeration: provider=cloud but no API key configured, will fall back to safe result");
            }
            this.apiUrl = cloudApiUrl;
            this.model = cloudModel;
            log.info("ContentModeration: provider=cloud, model={}", cloudModel);
        }
    }

    /**
     * 对消息内容进行违规分类。
     *
     * @param text 消息文本
     * @return 分类结果，不会为 null
     */
    public DeepSeekResult classifyMessage(String text) {
        if ("local".equals(provider) && (apiKey == null || apiKey.isBlank())) {
            // 本地 Ollama 不需要 API Key
        } else if (apiKey == null || apiKey.isBlank()) {
            return new DeepSeekResult("正常", 0.5, new String[]{}, "API未配置");
        }

        if (!concurrencyGuard.tryAcquireDeepSeek()) {
            log.warn("Concurrency limit exceeded, falling back to safe result");
            return new DeepSeekResult("正常", 0.5, new String[]{}, "服务繁忙");
        }

        try {
            return classifyWithRetry(text);
        } finally {
            concurrencyGuard.releaseDeepSeek();
        }
    }

    /**
     * 对用户跨群消息进行画像分析。
     *
     * @param userMessagesJson 用户消息 JSON 合集
     * @return 画像分析结果，失败返回 null
     */
    public DeepSeekResult analyzeProfile(String userMessagesJson) {
        if ("local".equals(provider) && (apiKey == null || apiKey.isBlank())) {
            // 本地 Ollama 不需要 API Key
        } else if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        if (!concurrencyGuard.tryAcquireDeepSeek()) {
            log.warn("Concurrency limit exceeded, profile analysis skipped");
            return null;
        }

        try {
            return profileWithRetry(userMessagesJson);
        } finally {
            concurrencyGuard.releaseDeepSeek();
        }
    }

    private DeepSeekResult classifyWithRetry(String text) {
        String requestBody = buildClassifyRequestBody(text);
        return doRequestWithRetry(requestBody, false);
    }

    private DeepSeekResult profileWithRetry(String userMessagesJson) {
        String prompt = buildProfilePrompt(userMessagesJson);
        String requestBody = """
                {
                    "model": "%s",
                    "messages": [{"role":"system","content":"你是跨群组画像分析师"}, {"role":"user","content": %s}],
                    "temperature": 0.2,
                    "max_tokens": 300
                }
                """.formatted(model, escapeJson(prompt));
        return doRequestWithRetry(requestBody, true);
    }

    private DeepSeekResult doRequestWithRetry(String requestBody, boolean isProfile) {
        Exception lastError = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofMillis(timeoutMs * (isProfile ? 2 : 1)))
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody));

                if (!"local".equals(provider) && apiKey != null && !apiKey.isBlank()) {
                    builder.header("Authorization", "Bearer " + apiKey);
                }

                HttpResponse<String> resp = client.send(builder.build(),
                        HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() == 429) {
                    log.warn("Rate limited (attempt {}/{}), retrying...", attempt, MAX_RETRIES);
                    Thread.sleep(RETRY_DELAY.toMillis() * attempt);
                    continue;
                }
                if (resp.statusCode() >= 500) {
                    log.warn("Server error {} (attempt {}/{})", resp.statusCode(), attempt, MAX_RETRIES);
                    Thread.sleep(RETRY_DELAY.toMillis());
                    continue;
                }

                return isProfile ? parseProfileResponse(resp.body()) : parseResponse(resp.body());

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return isProfile ? null : new DeepSeekResult("正常", 0.5, new String[]{}, "请求中断");
            } catch (Exception e) {
                lastError = e;
                log.warn("Request failed (attempt {}/{}): {}", attempt, MAX_RETRIES, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        if (isProfile) {
            log.error("Profile request failed after {} retries: {}", MAX_RETRIES,
                    lastError != null ? lastError.getMessage() : "unknown");
            return null;
        }
        log.error("Classify request failed after {} retries: {}", MAX_RETRIES,
                lastError != null ? lastError.getMessage() : "unknown");
        return new DeepSeekResult("正常", 0.5, new String[]{}, "服务调用失败");
    }

    private String buildClassifyRequestBody(String text) {
        String prompt = """
                你是一个内容安全分类器。请分析以下消息，返回严格JSON格式（不要markdown包裹）：
                {"category":"类别","confidence":0.XX,"keywords":["关键词1","关键词2"],"brief_reason":"简短理由"}

                类别只能是以下之一：正常,诈骗,政治,煽动,分裂,色情,赌博,普通广告
                confidence范围0-1，越确定数值越高。

                消息内容：
                """ + text;

        return """
                {
                    "model": "%s",
                    "messages": [{"role":"system","content":"你是严谨的内容安全分类器，只返回JSON"}, {"role":"user","content": %s}],
                    "temperature": 0.1,
                    "max_tokens": 200
                }
                """.formatted(model, escapeJson(prompt));
    }

    private String buildProfilePrompt(String userMessagesJson) {
        return """
                你是一个跨群组行为画像分析器。分析该用户今日在所有群组的发言记录，输出严格JSON：
                {
                    "overall_risk_level": "安全/关注/危险/死刑",
                    "category_distribution": {"色情":0.3,"广告":0.2,"正常":0.5},
                    "has_safe_haven_abuse": false,
                    "recommended_action": "放行/警告/扣分/标记/禁言/死刑"
                }
                用户今日消息记录：
                """ + userMessagesJson;
    }

    private String escapeJson(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    private DeepSeekResult parseResponse(String body) {
        try {
            var root = OBJECT_MAPPER.readTree(body);
            String content = root.path("choices").get(0).path("message").path("content").asText()
                    .replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

            var result = OBJECT_MAPPER.readTree(content);
            String category = result.path("category").asText("正常");
            double confidence = result.path("confidence").asDouble(0.5);

            var kwArray = result.path("keywords");
            String[] keywords = new String[kwArray.size()];
            for (int i = 0; i < kwArray.size(); i++) keywords[i] = kwArray.get(i).asText();

            return new DeepSeekResult(category, confidence, keywords,
                    result.path("brief_reason").asText(""));
        } catch (Exception e) {
            log.warn("Failed to parse response: {}", e.getMessage());
            return new DeepSeekResult("正常", 0.5, new String[]{}, "解析失败");
        }
    }

    private DeepSeekResult parseProfileResponse(String body) {
        try {
            var root = OBJECT_MAPPER.readTree(body);
            String content = root.path("choices").get(0).path("message").path("content").asText()
                    .replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

            var result = OBJECT_MAPPER.readTree(content);
            String riskLevel = result.path("overall_risk_level").asText("安全");
            String action = result.path("recommended_action").asText("放行");

            String category = switch (riskLevel) {
                case "死刑" -> "诈骗";
                case "危险" -> "色情";
                case "关注" -> "普通广告";
                default -> "正常";
            };
            double confidence = "死刑".equals(riskLevel) ? 0.9 : 0.7;

            return new DeepSeekResult(category, confidence, new String[]{},
                    "画像分析: " + action);
        } catch (Exception e) {
            log.warn("Failed to parse profile response: {}", e.getMessage());
            return null;
        }
    }
}
