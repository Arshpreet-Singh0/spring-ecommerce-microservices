package com.ecommerce.order_service.services;

import com.ecommerce.order_service.advices.ApiResponse;
import com.ecommerce.order_service.clients.InventoryFeignClient;
import com.ecommerce.order_service.clients.ProductFeignClient;
import com.ecommerce.order_service.dto.OrderItemDTO;
import com.ecommerce.order_service.dto.OrderRequestDTO;
import com.ecommerce.order_service.dto.OrderResponseDTO;
import com.ecommerce.order_service.dto.ProductDTO;
import com.ecommerce.order_service.dto.ReserveInventoryRequest;
import com.ecommerce.order_service.entities.Order;
import com.ecommerce.order_service.entities.OrderItem;
import com.ecommerce.order_service.entities.OrderStatus;
import com.ecommerce.order_service.exceptions.InventoryReservationException;
import com.ecommerce.order_service.exceptions.PriceMismatchException;
import com.ecommerce.order_service.exceptions.ProductNotFoundException;
import com.ecommerce.order_service.repositories.OrderRepository;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.modelmapper.ModelMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long ORDER_ID = 42L;
    private static final String KEY = "idem-key-1";

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ProductFeignClient productFeignClient;
    @Mock
    private InventoryFeignClient inventoryFeignClient;

    private OrderService orderService;

    /** Status of the order at the moment of each save() (the same instance is mutated between saves). */
    private final List<OrderStatus> savedStatuses = new ArrayList<>();

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, productFeignClient, inventoryFeignClient, new ModelMapper());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------------- new orders ----------------

    @Test
    void newOrder_isSavedCreatedThenPaymentPending_andReserveGroupsBySku() {
        stubNoExistingOrder();
        stubSaveRecordingStatus();
        // two products, but both share the same SKU -> one reserve line with summed quantity
        stubProducts(product(1L, "SKU-A", "10.00", null), product(2L, "SKU-A", "5.00", null));

        OrderResponseDTO response = orderService.createOrder(request(item(1L, 2, null), item(2L, 3, null)));

        assertThat(savedStatuses).containsExactly(OrderStatus.CREATED, OrderStatus.PAYMENT_PENDING);
        assertThat(response.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);

        ArgumentCaptor<ReserveInventoryRequest> reserve = ArgumentCaptor.forClass(ReserveInventoryRequest.class);
        verify(inventoryFeignClient).reserve(reserve.capture());
        assertThat(reserve.getValue().getOrderId()).isEqualTo(ORDER_ID.toString());
        assertThat(reserve.getValue().getItems())
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.getSku()).isEqualTo("SKU-A");
                    assertThat(i.getQty()).isEqualTo(5);
                });
    }

    @Test
    void newOrder_persistsUserIdAndIdempotencyKey() {
        stubNoExistingOrder();
        stubSaveRecordingStatus();
        stubProducts(product(1L, "SKU-A", "10.00", null));

        orderService.createOrder(request(item(1L, 1, null)));

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getValue().getIdempotencyKey()).isEqualTo(KEY);
    }

    @Nested
    class Pricing {

        @Test
        void discountIsApplied_andTotalIsSumOfPriceTimesQuantity() {
            stubNoExistingOrder();
            stubSaveRecordingStatus();
            // 120.00 at 10% -> 108.00 ; 50.00 with no discount -> 50.00
            stubProducts(product(1L, "SKU-A", "120.00", "10"), product(2L, "SKU-B", "50.00", null));

            OrderResponseDTO response = orderService.createOrder(
                    request(item(1L, 2, "108.00"), item(2L, 3, null)));

            ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
            verify(orderRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
            Order order = saved.getValue();

            assertThat(order.getItems()).extracting(OrderItem::getPrice)
                    .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                    .containsExactly(new BigDecimal("108.00"), new BigDecimal("50.00"));
            // 108 * 2 + 50 * 3 = 366
            assertThat(order.getTotalAmount()).isEqualByComparingTo("366.00");
            assertThat(response.getTotalAmount()).isEqualByComparingTo("366.00");
        }

        @Test
        void priceMismatch_throws_nothingSaved_inventoryNeverCalled() {
            stubNoExistingOrder();
            stubProducts(product(1L, "SKU-A", "120.00", "10"));

            assertThatThrownBy(() -> orderService.createOrder(request(item(1L, 1, "120.00"))))
                    .isInstanceOf(PriceMismatchException.class)
                    .hasMessageContaining("1");

            verify(orderRepository, never()).save(any());
            verifyNoInteractions(inventoryFeignClient);
        }
    }

    @Test
    void missingProduct_throwsProductNotFound() {
        stubNoExistingOrder();
        stubProducts(product(1L, "SKU-A", "10.00", null)); // product 2 is missing

        assertThatThrownBy(() -> orderService.createOrder(request(item(1L, 1, null), item(2L, 1, null))))
                .isInstanceOf(ProductNotFoundException.class);

        verify(orderRepository, never()).save(any());
        verifyNoInteractions(inventoryFeignClient);
    }

    @Test
    void duplicateProductIdsInRequest_areFetchedOnce() {
        stubNoExistingOrder();
        stubSaveRecordingStatus();
        stubProducts(product(1L, "SKU-A", "10.00", null));

        orderService.createOrder(request(item(1L, 1, null), item(1L, 2, null)));

        verify(productFeignClient).getProductsByIds(List.of(1L));
    }

    // ---------------- idempotency ----------------

    @Test
    void idempotentReplay_ofNonFailedOrder_returnsExisting_withoutDownstreamCalls() {
        Order existing = existingOrder(OrderStatus.PAYMENT_PENDING);
        when(orderRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));

        OrderResponseDTO response = orderService.createOrder(request(item(1L, 99, null)));

        assertThat(response.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(response.getTotalAmount()).isEqualByComparingTo(existing.getTotalAmount());
        verifyNoInteractions(productFeignClient, inventoryFeignClient);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void retryOfFailedOrder_usesPersistedItems_andEndsPaymentPending() {
        Order existing = existingOrder(OrderStatus.FAILED); // persisted: product 1 x 2
        when(orderRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));
        stubSaveRecordingStatus();
        stubProducts(product(1L, "SKU-A", "10.00", null));

        // the replayed body asks for something different: it must be ignored
        OrderResponseDTO response = orderService.createOrder(request(item(5L, 50, null)));

        verify(productFeignClient).getProductsByIds(List.of(1L));
        ArgumentCaptor<ReserveInventoryRequest> reserve = ArgumentCaptor.forClass(ReserveInventoryRequest.class);
        verify(inventoryFeignClient).reserve(reserve.capture());
        assertThat(reserve.getValue().getItems()).singleElement().satisfies(i -> {
            assertThat(i.getSku()).isEqualTo("SKU-A");
            assertThat(i.getQty()).isEqualTo(2);
        });
        assertThat(savedStatuses).containsExactly(OrderStatus.PAYMENT_PENDING);
        assertThat(response.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }

    @Test
    void requestWithoutIdempotencyKey_skipsLookup() {
        stubSaveRecordingStatus();
        stubProducts(product(1L, "SKU-A", "10.00", null));
        OrderRequestDTO dto = request(item(1L, 1, null));
        dto.setIdempotencyKey(null);

        orderService.createOrder(dto);

        verify(orderRepository, never()).findByIdempotencyKey(any());
    }

    // ---------------- inventory failures ----------------

    @Test
    void inventoryFailure_persistsFailed_andRethrows() {
        stubNoExistingOrder();
        stubSaveRecordingStatus();
        stubProducts(product(1L, "SKU-A", "10.00", null));
        doThrow(new RuntimeException("connection refused")).when(inventoryFeignClient).reserve(any());

        assertThatThrownBy(() -> orderService.createOrder(request(item(1L, 1, null))))
                .isInstanceOf(InventoryReservationException.class);

        // the last write is FAILED (committed before the exception propagates)
        assertThat(savedStatuses).containsExactly(OrderStatus.CREATED, OrderStatus.FAILED);
    }

    static Stream<Arguments> feignErrors() {
        return Stream.of(
                Arguments.of(feignError(409), HttpStatus.CONFLICT),
                Arguments.of(feignError(404), HttpStatus.NOT_FOUND),
                Arguments.of(feignError(500), HttpStatus.SERVICE_UNAVAILABLE),
                Arguments.of(new RuntimeException("timeout"), HttpStatus.SERVICE_UNAVAILABLE)
        );
    }

    @ParameterizedTest
    @MethodSource("feignErrors")
    void inventoryErrors_areTranslatedToClientStatus(RuntimeException error, HttpStatus expected) {
        stubNoExistingOrder();
        stubSaveRecordingStatus();
        stubProducts(product(1L, "SKU-A", "10.00", null));
        doThrow(error).when(inventoryFeignClient).reserve(any());

        assertThatThrownBy(() -> orderService.createOrder(request(item(1L, 1, null))))
                .isInstanceOfSatisfying(InventoryReservationException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(expected));
        assertThat(savedStatuses).last().isEqualTo(OrderStatus.FAILED);
    }

    // ---------------- helpers ----------------

    private void stubNoExistingOrder() {
        when(orderRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
    }

    private void stubSaveRecordingStatus() {
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            savedStatuses.add(o.getStatus());
            if (o.getId() == null) o.setId(ORDER_ID);
            return o;
        });
    }

    private void stubProducts(ProductDTO... products) {
        when(productFeignClient.getProductsByIds(anyList())).thenReturn(new ApiResponse<>(List.of(products)));
    }

    private static ProductDTO product(Long id, String sku, String price, String discountPercent) {
        ProductDTO p = new ProductDTO();
        p.setId(id);
        p.setSku(sku);
        p.setPrice(new BigDecimal(price));
        p.setDiscountPercent(discountPercent == null ? null : new BigDecimal(discountPercent));
        return p;
    }

    private static OrderItemDTO item(Long productId, int qty, String price) {
        OrderItemDTO i = new OrderItemDTO();
        i.setProductId(productId);
        i.setQuantity(qty);
        i.setPrice(price == null ? null : new BigDecimal(price));
        return i;
    }

    private static OrderRequestDTO request(OrderItemDTO... items) {
        OrderRequestDTO dto = new OrderRequestDTO();
        dto.setIdempotencyKey(KEY);
        dto.setItems(List.of(items));
        return dto;
    }

    private static Order existingOrder(OrderStatus status) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setUserId(USER_ID);
        order.setStatus(status);
        order.setIdempotencyKey(KEY);
        order.setTotalAmount(new BigDecimal("20.00"));
        OrderItem item = new OrderItem();
        item.setProductId(1L);
        item.setQuantity(2);
        item.setPrice(new BigDecimal("10.00"));
        order.addItem(item);
        return order;
    }

    private static FeignException feignError(int status) {
        Request request = Request.create(Request.HttpMethod.POST, "http://inventory-service/inventory/reserve",
                Map.of(), null, StandardCharsets.UTF_8, null);
        return FeignException.errorStatus("reserve", feign.Response.builder()
                .status(status)
                .reason("error")
                .request(request)
                .headers(Map.of())
                .build());
    }
}
