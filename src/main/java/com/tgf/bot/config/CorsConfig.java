package com.tgf.bot.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * CorsConfig — CORS 跨域配置。
 * 
 * 允许 Mini App 前端跨域访问 REST API。生产环境建议通过
 * cors.allowed-origins 配置具体域名，默认仅允许 localhost 开发环境。
 * @since 1.0
 */
@Configuration
public class CorsConfig {

    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    @Value("${cors.allowed-origins:http://localhost:*,http://127.0.0.1:*}")
    private String allowedOrigins;

    @PostConstruct
    public void init() {
        List<String> origins = Arrays.asList(allowedOrigins.split(","));
        log.info("CORS configured, {} allowed origins", origins.size());
        // 检查是否包含 localhost 通配符（开发环境默认值）
        boolean hasLocalhostWildcard = origins.stream().anyMatch(o -> o.contains("localhost:*") || o.contains("127.0.0.1:*"));
        if (hasLocalhostWildcard) {
            log.warn("============================================================");
            log.warn("⚠️  CORS 配置包含 localhost 通配符");
            log.warn("   生产环境请配置具体域名，避免使用通配符");
            log.warn("   配置项: cors.allowed-origins");
            log.warn("============================================================");
        }
    }

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // 解析允许的来源，逗号分隔
        List<String> origins = Arrays.asList(allowedOrigins.split(","));
        for (String origin : origins) {
            config.addAllowedOriginPattern(origin.trim());
        }
        // 限制允许的请求头（只允许必要的，而不是全部）
        config.setAllowedHeaders(List.of(
            "Content-Type",
            "Authorization",
            "X-Admin-Token",
            "X-User-Id",
            "Accept",
            "Origin",
            "X-Requested-With"
        ));
        // 限制允许的方法
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        // 允许携带凭证（Cookie、Authorization 头等）
        config.setAllowCredentials(true);
        // 预检请求缓存时间
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        // webhook 端点不设 CORS（Telegram 服务器直连）
        return new CorsFilter(source);
    }
}
