package com.tgf.bot.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ElasticsearchConfig — Elasticsearch 客户端配置。
 * 手动构建 RestClient，支持多节点配置、连接池调优、
 * 并在启动时自动创建评分索引（若不存在）。
 * @since 1.0
 */
@Configuration
public class ElasticsearchConfig {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchConfig.class);

    @Value("${elasticsearch.uris:http://localhost:9900}")
    private String uris;

    @Value("${elasticsearch.rating-index:rating_v2}")
    private String ratingIndex;

    @Bean
    public ElasticsearchClient elasticsearchClient() {
        String[] parts = uris.split(",");
        HttpHost[] hosts = new HttpHost[parts.length];
        for (int i = 0; i < parts.length; i++) {
            hosts[i] = HttpHost.create(parts[i].trim());
        }

        var builder = RestClient.builder(hosts)
            .setRequestConfigCallback(c -> c
                .setConnectTimeout(5000)
                .setSocketTimeout(30000)
                .setConnectionRequestTimeout(5000))
            .setHttpClientConfigCallback(c -> c
                .setMaxConnTotal(100)
                .setMaxConnPerRoute(50));

        var restClient = builder.build();
        var transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        var client = new ElasticsearchClient(transport);

        // 自动创建评分索引
        createRatingIndex(client);

        log.info("ES client initialized: {} ({} hosts)", uris, hosts.length);
        return client;
    }

    private void createRatingIndex(ElasticsearchClient client) {
        try {
            boolean exists = client.indices().exists(e -> e.index(ratingIndex)).value();
            if (!exists) {
                client.indices().create(c -> c
                    .index(ratingIndex)
                    .settings(s -> s
                        .numberOfShards("3")
                        .numberOfReplicas("1")
                        .refreshInterval(t -> t.time("5s")))
                    .mappings(m -> m
                        .properties("entityType", p -> p.byte_(b -> b))
                        .properties("entityId", p -> p.long_(b -> b))
                        .properties("userId", p -> p.long_(b -> b))
                        .properties("score", p -> p.byte_(b -> b))
                        .properties("ip", p -> p.keyword(b -> b))
                        .properties("captchaId", p -> p.keyword(b -> b))
                        .properties("createTime", p -> p.long_(b -> b))
                        .properties("updateTime", p -> p.long_(b -> b))
                    ));
                log.info("Created ES rating index: {}", ratingIndex);
            }
        } catch (Exception e) {
            log.warn("Failed to create rating index (may already exist): {}", e.getMessage());
        }
    }
}
