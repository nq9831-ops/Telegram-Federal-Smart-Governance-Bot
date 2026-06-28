package com.tgf.bot.config;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * ApiAuthAspect — API 鉴权切面。
 * 使用固定的 admin-token 校验管理员身份，前端通过 HTTP Header 传递。
 * 同时支持用户身份校验（X-User-Id 头 + 参数比对）。
 * @since 1.0
 */
@Component
public class ApiAuthAspect {

    private static final Logger log = LoggerFactory.getLogger(ApiAuthAspect.class);
    private static final int MIN_TOKEN_LENGTH = 16;

    @Value("${admin.token:}")
    private String adminToken;

    @PostConstruct
    public void init() {
        if (adminToken == null || adminToken.isBlank()) {
            log.warn("============================================================");
            log.warn("⚠️  安全警告: admin.token 未配置！");
            log.warn("   管理员 API 将全部拒绝访问，请配置 ADMIN_TOKEN 环境变量");
            log.warn("   建议使用至少 32 位的随机字符串");
            log.warn("============================================================");
        } else if (adminToken.length() < MIN_TOKEN_LENGTH) {
            log.warn("============================================================");
            log.warn("⚠️  安全警告: admin.token 强度不足（至少 {} 位）", MIN_TOKEN_LENGTH);
            log.warn("   当前长度: {} 位", adminToken.length());
            log.warn("   建议使用至少 32 位的随机字符串");
            log.warn("============================================================");
        } else {
            log.info("Admin token configured, length: {} chars", adminToken.length());
        }
    }

    /**
     * 校验是否为管理员（检查 X-Admin-Token 头）
     */
    public boolean checkAdmin() {
        try {
            HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
            String token = request.getHeader("X-Admin-Token");
            return constantTimeEquals(adminToken, token != null ? token : "");
        } catch (Exception e) {
            log.debug("checkAdmin failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 校验用户身份：检查 X-User-Id 头与 userId 参数是否一致
     */
    public boolean checkUserIdentity(long requestUserId) {
        try {
            HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
            String headerUserId = request.getHeader("X-User-Id");
            if (headerUserId == null) return false;
            return Long.parseLong(headerUserId) == requestUserId;
        } catch (Exception e) {
            log.debug("checkUserIdentity failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 常量时间字符串比较，防止时序攻击。
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
