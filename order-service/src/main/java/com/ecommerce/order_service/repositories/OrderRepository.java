package com.ecommerce.order_service.repositories;

import com.ecommerce.order_service.entities.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    // Items are needed for the response and for retrying a FAILED order; fetch them in the same
    // query since OrderService.createOrder runs outside a transaction.
    @EntityGraph(attributePaths = "items")
    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    @Query("""
    SELECT DISTINCT o
    FROM Order o
    LEFT JOIN FETCH o.items
    WHERE o.userId = :userId
    ORDER BY o.userId ASC
""")
    List<Order> findOrdersWithItems(Long userId);
}