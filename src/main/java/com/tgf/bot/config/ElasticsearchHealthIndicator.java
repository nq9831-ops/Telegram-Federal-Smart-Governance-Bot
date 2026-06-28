package com.tgf.bot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * ElasticsearchHealthIndicator — ES 健康检查探针。
 * 
 * 检查 ES 集群状态，用于 Actuator 健康端点和登录页状态展示。
 */
@Component
public class ElasticsearchHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchHealthIndicator.class);

    private final HttpClient httpClient;
    private final String esUrl;

    public ElasticsearchHealthIndicator(@Value("${elasticsearch.uris:http://localhost:9900}") String esUris) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();
        // 取第一个 URI
        String first = esUris.split(",")[0].trim();
        this.esUrl = first.endsWith("/") ? first + "_cluster/health" : first + "/_cluster/health";
    }

    @Override
    public Health health() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(esUrl))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return Health.up()
                        .withDetail("cluster", esUrl)
                        .withDetail("status", "green/yellow")
                        .build();
            } else {
                return Health.down()
                        .withDetail("cluster", esUrl)
                        .withDetail("statusCode", response.statusCode())
                        .build();
            }
        } catch (Exception e) {
            log.warn("ES health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("cluster", esUrl)
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
