package com.tgf.bot.config;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebhookIpFilter — Webhook 来源 IP 校验 + 简单限流。
 * <p>限制：</p>
 * <ul>
 *   <li>仅 /webhook/** 路径生效</li>
 *   <li>校验来源 IP 是否在 Telegram 官方 IP 段内</li>
 *   <li>简单单 IP 令牌桶限流（防止刷爆）</li>
 *   <li>仅信任配置的反向代理 IP 的 X-Forwarded-For 头</li>
 * </ul>
 * @since 1.0
 */
@Component
public class WebhookIpFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(WebhookIpFilter.class);

    private final List<String> allowedCidrs;
    private final List<String> trustedProxies;
    private final int rateLimitPerSecond;
    private final ConcurrentHashMap<String, RateBucket> buckets = new ConcurrentHashMap<>();

    public WebhookIpFilter(
            @Value("${webhook.allowed-ips:}") String allowedIps,
            @Value("${webhook.trusted-proxies:}") String trustedProxiesStr,
            @Value("${webhook.rate-limit-per-second:50}") int rateLimit) {
        this.allowedCidrs = parseCidrList(allowedIps);
        this.trustedProxies = parseCidrList(trustedProxiesStr);
        this.rateLimitPerSecond = rateLimit;
    }

    @PostConstruct
    public void init() {
        log.info("Webhook IP filter initialized");
        log.info("  - Allowed CIDRs: {} rules", allowedCidrs.size());
        log.info("  - Trusted proxies: {} rules", trustedProxies.size());
        log.info("  - Rate limit: {}/s per IP", rateLimitPerSecond);
        if (trustedProxies.isEmpty()) {
            log.warn("============================================================");
            log.warn("⚠️  webhook.trusted-proxies 未配置");
            log.warn("   将直接使用 remoteAddr，不信任 X-Forwarded-For 头");
            log.warn("   如有反向代理（Nginx/Cloudflare），请配置其 IP 段");
            log.warn("============================================================");
        }
    }

    private List<String> parseCidrList(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : input.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        if (!path.startsWith("/webhook/")) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = getClientIp(request);

        // IP 白名单校验（仅当配置了白名单时）
        if (!allowedCidrs.isEmpty() && !allowedCidrs.get(0).isBlank()) {
            if (!isTelegramIp(clientIp)) {
                log.warn("Blocked webhook request from non-Telegram IP: {}", clientIp);
                response.sendError(403, "Forbidden: IP not in allowlist");
                return;
            }
        }

        // 限流检查
        RateBucket bucket = buckets.computeIfAbsent(clientIp, k -> new RateBucket(rateLimitPerSecond));
        if (!bucket.tryAcquire()) {
            log.warn("Webhook rate limit exceeded for IP: {}", clientIp);
            response.sendError(429, "Too Many Requests");
            return;
        }

        chain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();

        // 如果没有配置可信代理，直接使用 remoteAddr，不信任任何 XFF 头
        if (trustedProxies.isEmpty()) {
            return remoteAddr;
        }

        // 检查 remoteAddr 是否是可信代理
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }

        // 从 X-Forwarded-For 右边开始，跳过所有可信代理，取第一个非可信代理 IP
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] ips = xff.split(",");
            // 从右往左遍历，跳过所有可信代理
            for (int i = ips.length - 1; i >= 0; i--) {
                String ip = ips[i].trim();
                if (!isTrustedProxy(ip)) {
                    return ip;
                }
            }
        }

        // 如果 XFF 中所有 IP 都是可信代理，返回 remoteAddr
        return remoteAddr;
    }

    private boolean isTrustedProxy(String ipStr) {
        try {
            InetAddress addr = InetAddress.getByName(ipStr);
            byte[] ipBytes = addr.getAddress();
            for (String cidr : trustedProxies) {
                if (matchesCidr(ipBytes, cidr)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("Trusted proxy IP parse failed: {}", ipStr);
        }
        return false;
    }

    private boolean isTelegramIp(String ipStr) {
        try {
            InetAddress addr = InetAddress.getByName(ipStr);
            byte[] ipBytes = addr.getAddress();
            for (String cidr : allowedCidrs) {
                if (cidr.isBlank()) continue;
                if (matchesCidr(ipBytes, cidr.trim())) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("IP parse failed: {}", ipStr);
        }
        return false;
    }

    private static boolean matchesCidr(byte[] ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            InetAddress cidrAddr = InetAddress.getByName(parts[0]);
            byte[] cidrBytes = cidrAddr.getAddress();
            if (ip.length != cidrBytes.length) return false;

            int prefix = parts.length > 1 ? Integer.parseInt(parts[1]) : ip.length * 8;
            int fullBytes = prefix / 8;
            int bitsRemaining = prefix % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (ip[i] != cidrBytes[i]) return false;
            }
            if (bitsRemaining > 0 && fullBytes < ip.length) {
                int mask = (0xFF << (8 - bitsRemaining)) & 0xFF;
                if ((ip[fullBytes] & mask) != (cidrBytes[fullBytes] & mask)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static class RateBucket {
        private final int limit;
        private int count = 0;
        private long windowStart = System.currentTimeMillis();

        RateBucket(int limit) {
            this.limit = limit;
        }

        synchronized boolean tryAcquire() {
            long now = System.currentTimeMillis();
            if (now - windowStart > 1000) {
                count = 0;
                windowStart = now;
            }
            count++;
            return count <= limit;
        }
    }
}
