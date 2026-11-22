package com.ecommerce.search_service.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 * Elasticsearch Java client 9.x (managed by Spring Boot 4).
 * Uses the Rest5Client transport (Apache HttpClient 5) and the Jackson 3 mapper,
 * matching the Jackson version Spring Boot 4 uses everywhere else.
 */
@Configuration
public class ElasticsearchConfig {

    @Value("${spring.elasticsearch.uris:http://localhost:9200}")
    private String elasticsearchUri;

    @Bean(destroyMethod = "close")
    public Rest5Client elasticsearchRestClient() {
        return Rest5Client.builder(URI.create(elasticsearchUri)).build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(Rest5Client restClient) {
        return new ElasticsearchClient(new Rest5ClientTransport(restClient, new Jackson3JsonpMapper()));
    }
}
