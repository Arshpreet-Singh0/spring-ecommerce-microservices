package com.ecommerce.order_service.services;

import com.ecommerce.order_service.clients.InventoryFeignClient;
import com.ecommerce.order_service.clients.ProductFeignClient;
import com.ecommerce.order_service.dto.*;
import com.ecommerce.order_service.entities.Order;
import com.ecommerce.order_service.entities.OrderItem;
import com.ecommerce.order_service.entities.OrderStatus;
import com.ecommerce.order_service.exceptions.InventoryReservationException;
import com.ecommerce.order_service.exceptions.PriceMismatchException;
import com.ecommerce.order_service.exceptions.ProductNotFoundException;
import com.ecommerce.order_service.repositories.OrderRepository;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductFeignClient productFeignClient;
    private final InventoryFeignClient inventoryFeignClient;
    private final ModelMapper modelMapper;

    /**
     * Deliberately NOT {@code @Transactional}: see docs/decisions/001-order-failure-state.md.
     * <p>
     * Each {@code orderRepository.save(..)} runs in its own short transaction, so:
     * <ul>
     *   <li>the order is committed (status CREATED) before inventory is called,</li>
     *   <li>no DB connection/transaction is held open during the remote Feign call,</li>
     *   <li>a FAILED status is committed and survives the exception we throw afterwards.</li>
     * </ul>
     */
    public OrderResponseDTO createOrder(OrderRequestDTO dto) {
        log.info("OrderService: createOrder {}", dto);

        // 1. Idempotency check — a replayed request for a non-failed order must not
        //    hit any downstream service, so short-circuit before fetching products.
        Order existing = getExistingOrder(dto.getIdempotencyKey());
        if (existing != null && existing.getStatus() != OrderStatus.FAILED) {
            log.info("Idempotent replay for key {}, returning order {}", dto.getIdempotencyKey(), existing.getId());
            return mapToResponse(existing);
        }

        // 2. Retry of a previously FAILED order: reserve again using the PERSISTED items
        //    (the source of truth), not whatever the replayed request body contains.
        if (existing != null) {
            log.info("Retrying inventory reservation for FAILED order {}", existing.getId());
            Map<Long, ProductDTO> productMap = fetchAndValidateProducts(productIdsOf(existing));
            return reserveInventoryAndUpdateStatus(existing, productMap);
        }

        // 3. New order: fetch & validate products, build and persist it (tx #1 commits here)
        Map<Long, ProductDTO> productMap = fetchAndValidateProducts(
                dto.getItems().stream().map(OrderItemDTO::getProductId).toList());
        Order savedOrder = orderRepository.save(buildOrder(dto, productMap));

        // 4. Reserve inventory outside any transaction, then record the outcome (tx #2)
        return reserveInventoryAndUpdateStatus(savedOrder, productMap);
    }

    // ---------------- HELPERS ----------------

    private Order getExistingOrder(String idempotencyKey) {
        if (idempotencyKey == null) return null;
        return orderRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
    }

    private List<Long> productIdsOf(Order order) {
        return order.getItems().stream().map(OrderItem::getProductId).toList();
    }

    private Map<Long, ProductDTO> fetchAndValidateProducts(List<Long> requestedIds) {

        List<Long> productIds = requestedIds.stream().distinct().toList();

        List<ProductDTO> products = productFeignClient.getProductsByIds(productIds).getData();

        if (products.size() != productIds.size()) {
            throw new ProductNotFoundException("Some products are missing");
        }

        return products.stream()
                .collect(Collectors.toMap(ProductDTO::getId, p -> p));
    }

    private Order buildOrder(OrderRequestDTO dto, Map<Long, ProductDTO> productMap) {

        Long userId = (Long) SecurityContextHolder.getContext()
                .getAuthentication()
                .getPrincipal();

        Order order = new Order();
        order.setUserId(userId);
        // CREATED until inventory confirms the reservation
        order.setStatus(OrderStatus.CREATED);
        order.setIdempotencyKey(dto.getIdempotencyKey());

        BigDecimal total = BigDecimal.ZERO;

        for (OrderItemDTO itemDTO : dto.getItems()) {

            ProductDTO product = productMap.get(itemDTO.getProductId());

            BigDecimal finalPrice = calculateFinalPrice(product);

            validatePrice(itemDTO, finalPrice);

            OrderItem item = new OrderItem();
            item.setProductId(itemDTO.getProductId());
            item.setQuantity(itemDTO.getQuantity());
            item.setPrice(finalPrice);

            order.addItem(item);

            total = total.add(
                    finalPrice.multiply(BigDecimal.valueOf(itemDTO.getQuantity()))
            );
        }

        order.setTotalAmount(total);
        return order;
    }

    private BigDecimal calculateFinalPrice(ProductDTO product) {
        BigDecimal price = product.getPrice();

        if (product.getDiscountPercent() == null) return price;

        BigDecimal discount = price.multiply(product.getDiscountPercent())
                .divide(BigDecimal.valueOf(100));

        return price.subtract(discount);
    }

    private void validatePrice(OrderItemDTO itemDTO, BigDecimal finalPrice) {
        if (itemDTO.getPrice() != null &&
                itemDTO.getPrice().compareTo(finalPrice) != 0) {
            throw new PriceMismatchException("Price mismatch for product: " + itemDTO.getProductId());
        }
    }

    /**
     * Calls inventory-service and persists the outcome. Must be invoked with no surrounding
     * transaction: the FAILED save below commits immediately, so throwing afterwards does not
     * roll it back — which is what lets a later request with the same idempotency key retry.
     */
    private OrderResponseDTO reserveInventoryAndUpdateStatus(Order order, Map<Long, ProductDTO> productMap) {

        try {
            inventoryFeignClient.reserve(buildReserveRequest(order, productMap));
        } catch (Exception e) {
            log.warn("Inventory reservation failed for order {}: {}", order.getId(), e.getMessage());
            order.setStatus(OrderStatus.FAILED);
            orderRepository.save(order);
            throw toReservationException(order, e);
        }

        order.setStatus(OrderStatus.PAYMENT_PENDING);
        Order saved = orderRepository.save(order);
        log.info("Order {} reserved, status {}", saved.getId(), saved.getStatus());
        return mapToResponse(saved);
    }

    /** Translate inventory-service's HTTP status into a meaningful status for our client. */
    private InventoryReservationException toReservationException(Order order, Exception e) {
        if (e instanceof FeignException.Conflict) {
            return new InventoryReservationException(
                    "Insufficient stock for order " + order.getId(), HttpStatus.CONFLICT);
        }
        if (e instanceof FeignException.NotFound) {
            return new InventoryReservationException(
                    "Unknown SKU in order " + order.getId(), HttpStatus.NOT_FOUND);
        }
        return new InventoryReservationException(
                "Inventory service unavailable, order " + order.getId() + " can be retried",
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    private ReserveInventoryRequest buildReserveRequest(Order order, Map<Long, ProductDTO> productMap) {

        ReserveInventoryRequest reserveDTO = new ReserveInventoryRequest();
        reserveDTO.setOrderId(order.getId().toString());

        List<ReserveInventoryRequest.Item> items = order.getItems().stream()
                .collect(Collectors.groupingBy(
                        item -> productMap.get(item.getProductId()).getSku(),
                        Collectors.summingInt(OrderItem::getQuantity)
                ))
                .entrySet()
                .stream()
                .map(entry -> {
                    ReserveInventoryRequest.Item i = new ReserveInventoryRequest.Item();
                    i.setSku(entry.getKey());
                    i.setQty(entry.getValue());
                    return i;
                })
                .toList();

        reserveDTO.setItems(items);
        return reserveDTO;
    }

    private OrderResponseDTO mapToResponse(Order order) {
        return modelMapper.map(order, OrderResponseDTO.class);
    }
}
