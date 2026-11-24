package com.ecommerce.inventory_service.consumer;

import com.ecommerce.inventory_service.entities.Inventory;
import com.ecommerce.inventory_service.repositories.InventoryRepository;
import com.ecommerce.inventory_service.support.AbstractIntegrationTest;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * product-created-topic -> inventory row, end to end through a real Kafka broker and Postgres.
 * Records are sent as raw JSON with the logical {@code __TypeId__} header product-service sets.
 */
class ProductKafkaConsumerIT extends AbstractIntegrationTest {

    private static final String TOPIC = "product-created-topic";

    @Autowired
    private InventoryRepository inventoryRepository;

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
    void productCreated_createsInventoryRow() {
        String sku = "KAFKA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        send(sku, 25);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Inventory inventory = inventoryRepository.findBySku(sku).orElseThrow();
            assertThat(inventory.getTotalQuantity()).isEqualTo(25);
            assertThat(inventory.getReservedQuantity()).isZero();
            assertThat(inventory.getAvailableQuantity()).isEqualTo(25);
        });
    }

    @Test
    void sameEventTwice_stillOneRow() {
        String sku = "KAFKA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        send(sku, 5);
        send(sku, 5);
        // a third, different SKU sent last on the same key/partition: once it is consumed,
        // both duplicates before it have been processed too
        String marker = sku + "-M";
        send(marker, 1, sku);

        await().atMost(Duration.ofSeconds(30))
                .until(() -> inventoryRepository.findBySku(marker).isPresent());
        assertThat(inventoryRepository.findAll()).filteredOn(i -> i.getSku().equals(sku)).hasSize(1);
    }

    private void send(String sku, int qty) {
        send(sku, qty, sku);
    }

    private void send(String sku, int qty, String key) {
        String json = """
                {"id": 1, "name": "Trail runner", "sku": "%s", "price": 120.00, "totalQuantity": %d}
                """.formatted(sku, qty);
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, key, json);
        record.headers().add("__TypeId__", "productCreated".getBytes(StandardCharsets.UTF_8));
        try {
            producer.send(record).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
