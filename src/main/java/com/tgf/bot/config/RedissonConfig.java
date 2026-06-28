package com.tgf.bot.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;

/**
 * RedissonConfig — Redisson 客户端配置。
 * 
 * 手动创建 RedissonClient 避免与默认自动配置冲突。
 * 优先加载 classpath 下的 redisson.yaml，回退为本地单节点默认配置。
 * @since 1.0
 */
@Configuration
public class RedissonConfig {

    private static final Logger log = LoggerFactory.getLogger(RedissonConfig.class);

    @Bean
    public RedissonClient redissonClient() {
        // 从 classpath 加载配置文件
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("redisson.yaml")) {
            if (is != null) {
                Config config = Config.fromYAML(is);
                RedissonClient client = Redisson.create(config);
                log.info("Redisson client initialized");
                return client;
            }
        } catch (IOException e) {
            log.warn("Failed to load redisson.yaml, using defaults: {}", e.getMessage());
        }

        // 默认配置
        Config config = new Config();
        config.useSingleServer()
            .setAddress("redis://localhost:6379")
            .setConnectionPoolSize(32)
            .setConnectionMinimumIdleSize(8)
            .setTimeout(3000)
            .setRetryAttempts(3)
            .setRetryInterval(1500);
        return Redisson.create(config);
    }
}
