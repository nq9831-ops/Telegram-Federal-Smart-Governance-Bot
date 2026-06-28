package com.tgf.bot.config;

import com.pengrad.telegrambot.TelegramBot;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;

/**
 * BotConfig — Telegram Bot 客户端配置（生产环境）。
 * 当 bot.token 配置了真实值（非 dummy:token 前缀）时，创建真实 TelegramBot 实例。
 * 支持 SOCKS5 代理（通过 bot.proxy.enabled/host/port 配置）。
 * @since 1.0
 */
@Configuration
@ConditionalOnExpression("!'${bot.token:dummy:token}'.startsWith('dummy:')")
public class BotConfig {

    private static final Logger log = LoggerFactory.getLogger(BotConfig.class);

    @Value("${bot.proxy.enabled:false}")
    private boolean proxyEnabled;

    @Value("${bot.proxy.socks5-host:127.0.0.1}")
    private String proxyHost;

    @Value("${bot.proxy.socks5-port:7890}")
    private int proxyPort;

    @Bean
    public TelegramBot telegramBot(@Value("${bot.token}") String token) {
        String masked = token.length() > 8 ? "..." + token.substring(token.length() - 6) : "***";
        log.info("TelegramBot initialized, token suffix: {}", masked);

        if (proxyEnabled) {
            log.info("SOCKS5 proxy enabled: {}:{}", proxyHost, proxyPort);
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(proxyHost, proxyPort));
            OkHttpClient client = new OkHttpClient.Builder()
                    .proxy(proxy)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build();
            return new TelegramBot.Builder(token).okHttpClient(client).build();
        }

        return new TelegramBot(token);
    }
}
