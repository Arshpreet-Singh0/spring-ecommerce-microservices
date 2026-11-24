package com.ecommerce.order_service.repositories;

import com.ecommerce.order_service.entities.Order;
import com.ecommerce.order_service.entities.OrderItem;
import com.ecommerce.order_service.entities.OrderStatus;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository behaviour against real Postgres 17 (not H2).
 * Runs WITHOUT a test transaction: OrderService.createOrder is deliberately non-transactional,
 * so the repository must work without an open session, exactly as it does in production.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("it")
class OrderRepositoryIT {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static {
        POSTGRES.start();
    }

    @Autowired
    private OrderRepository orderRepository;

    @AfterEach
    void cleanUp() {
        orderRepository.deleteAll();
    }

    @Test
    void idempotencyKey_isUnique() {
        orderRepository.save(order("key-1", 1L));

        assertThatThrownBy(() -> orderRepository.save(order("key-1", 2L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByIdempotencyKey_fetchesItems_withoutAnOpenSession() {
        Order saved = orderRepository.save(order("key-2", 1L, 2L));

        Order found = orderRepository.findByIdempotencyKey("key-2").orElseThrow();

        // the session is closed here: lazy items would throw LazyInitializationException
        assertThat(Hibernate.isInitialized(found.getItems())).isTrue();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getItems()).extracting(OrderItem::getProductId).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void findByIdempotencyKey_unknownKey_isEmpty() {
        assertThat(orderRepository.findByIdempotencyKey("missing")).isEmpty();
    }

    private static Order order(String key, Long... productIds) {
        Order order = new Order();
        order.setUserId(7L);
        order.setStatus(OrderStatus.CREATED);
        order.setIdempotencyKey(key);
        order.setTotalAmount(BigDecimal.TEN);
        for (Long productId : productIds) {
            OrderItem item = new OrderItem();
            item.setProductId(productId);
            item.setQuantity(1);
            item.setPrice(BigDecimal.TEN);
            order.addItem(item);
        }
        return order;
    }
}
