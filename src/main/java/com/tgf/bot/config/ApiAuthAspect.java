package com.tgf.bot.config;

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

    @Value("${api.admin-token:admin-token-please-change}")
    private String adminToken;

    /**
     * 校验是否为管理员（检查 X-Admin-Token 头）
     */
    public boolean checkAdmin() {
        try {
            HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
            String token = request.getHeader("X-Admin-Token");
            return adminToken.equals(token);
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
}
