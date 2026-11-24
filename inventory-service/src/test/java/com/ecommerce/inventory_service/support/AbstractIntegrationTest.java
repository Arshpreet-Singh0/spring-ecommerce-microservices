package com.ecommerce.inventory_service.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for inventory-service integration tests.
 * <p>
 * Singleton containers: started once per JVM (static initializer) and shared by every IT class,
 * so Postgres and Kafka start once per module rather than once per class or test, and Spring's
 * cached application context keeps pointing at live containers. Ryuk removes them at JVM exit.
 */
@SpringBootTest
@ActiveProfiles("it")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withInitScript("db/shedlock.sql");

    @ServiceConnection
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1");

    static {
        POSTGRES.start();
        KAFKA.start();
    }
}
