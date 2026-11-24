package com.ecommerce.search_service;

import com.ecommerce.search_service.documents.ProductSearchDocument;
import com.ecommerce.search_service.services.SearchService;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Kafka -> search-service -> Elasticsearch, end to end: product/inventory events published as raw
 * JSON with the logical {@code __TypeId__} headers the producers set, then queried through
 * {@link SearchService} until the index reflects them (Awaitility, no sleeps).
 */
@SpringBootTest
@ActiveProfiles("it")
class ProductCreatedFlowIT {

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1");

    static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:9.2.3")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    static {
        KAFKA.start();
        ELASTICSEARCH.start();
    }

    /** ElasticsearchConfig builds its own client from this property, so no @ServiceConnection here. */
    @DynamicPropertySource
    static void elasticsearchProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", () -> "http://" + ELASTICSEARCH.getHttpHostAddress());
    }

    @Autowired
    private SearchService searchService;

    private KafkaProducer<String, String> producer;

    @BeforeEach
    void setUp() {
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
    }

    @AfterEach
    void tearDown() {
        producer.close();
    }

    @Test
    void productCreated_becomesSearchable_andInventoryUpdateFlipsInStock() {
        String sku = uniqueSku();
        send("product-created-topic", "productCreated", sku, """
                {"id": 1, "name": "Trail runner %s", "description": "Lightweight trail shoe",
                 "sku": "%s", "price": 120.00, "brand": "Acme", "category": "shoes", "totalQuantity": 5}
                """.formatted(sku, sku));

        ProductSearchDocument indexed = awaitDocument(sku, "trail");
        assertThat(indexed.getName()).startsWith("Trail runner");
        assertThat(indexed.getInStock()).isTrue();

        send("inventory-updated-topic", "inventoryUpdated", sku, """
                {"sku": "%s", "quantity": 0}
                """.formatted(sku));

        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() ->
                assertThat(find(sku, "trail")).extracting(ProductSearchDocument::getInStock).isEqualTo(false));
    }

    @Test
    void priceRangeFilter_includesAndExcludesByPrice() {
        String sku = uniqueSku();
        send("product-created-topic", "productCreated", sku, """
                {"id": 2, "name": "Budget sandal %s", "description": "Cheap", "sku": "%s",
                 "price": 15.00, "totalQuantity": 0}
                """.formatted(sku, sku));

        ProductSearchDocument indexed = awaitDocument(sku, "sandal");
        assertThat(indexed.getInStock()).isFalse();

        assertThat(searchService.searchProducts(sku, 10.0, 20.0, 0, 50))
                .extracting(ProductSearchDocument::getSku).contains(sku);
        assertThat(searchService.searchProducts(sku, 50.0, null, 0, 50))
                .extracting(ProductSearchDocument::getSku).doesNotContain(sku);
        assertThat(searchService.searchProducts(sku, null, 10.0, 0, 50))
                .extracting(ProductSearchDocument::getSku).doesNotContain(sku);
    }

    private ProductSearchDocument awaitDocument(String sku, String keyword) {
        // ignoreExceptions: the "products" index only exists once the first document is indexed
        return await().atMost(Duration.ofSeconds(60)).ignoreExceptions()
                .until(() -> find(sku, keyword), doc -> doc != null);
    }

    private ProductSearchDocument find(String sku, String keyword) {
        List<ProductSearchDocument> hits = searchService.searchProducts(keyword, null, null, 0, 100);
        return hits.stream().filter(d -> sku.equals(d.getSku())).findFirst().orElse(null);
    }

    private void send(String topic, String typeId, String key, String json) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, json);
        record.headers().add("__TypeId__", typeId.getBytes(StandardCharsets.UTF_8));
        try {
            producer.send(record).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String uniqueSku() {
        return "SKU" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}
