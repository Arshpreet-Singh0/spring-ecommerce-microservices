package com.ecommerce.order_service.controllers;

import com.ecommerce.order_service.config.AppConfig;
import com.ecommerce.order_service.config.WebSecurityConfig;
import com.ecommerce.order_service.dto.OrderResponseDTO;
import com.ecommerce.order_service.entities.OrderStatus;
import com.ecommerce.order_service.filters.JwtAuthFilter;
import com.ecommerce.order_service.security.JwtAccessDeniedHandler;
import com.ecommerce.order_service.security.JwtAuthenticationEntryPoint;
import com.ecommerce.order_service.services.JwtService;
import com.ecommerce.order_service.services.OrderService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = OrderController.class, properties = "jwt.secretKey=" + OrderControllerWebMvcTest.SECRET)
@Import({WebSecurityConfig.class, JwtAuthFilter.class, JwtService.class,
        JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class, AppConfig.class})
class OrderControllerWebMvcTest {

    static final String SECRET = "webmvc-test-secret-key-at-least-32-bytes";

    private static final String VALID_BODY = """
            {"idempotencyKey": "k-1", "items": [{"productId": 1, "quantity": 2}]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Test
    void createOrder_withoutToken_is401() throws Exception {
        mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(orderService);
    }

    @Test
    void createOrder_withEmptyItems_is400() throws Exception {
        mockMvc.perform(post("/orders").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\": \"k-1\", \"items\": []}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.apiError.subErrors", hasItem("items must not be empty")));
        verifyNoInteractions(orderService);
    }

    @Test
    void createOrder_withoutIdempotencyKey_is400() throws Exception {
        mockMvc.perform(post("/orders").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\": [{\"productId\": 1, \"quantity\": 2}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.apiError.subErrors", hasItem("idempotencyKey is required")));
        verifyNoInteractions(orderService);
    }

    @Test
    void createOrder_withInvalidItem_is400() throws Exception {
        mockMvc.perform(post("/orders").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\": \"k-1\", \"items\": [{\"quantity\": 0}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.apiError.subErrors", hasItem("productId is required")))
                .andExpect(jsonPath("$.apiError.subErrors", hasItem("quantity must be positive")));
    }

    @Test
    void createOrder_withValidBody_isDelegatedToTheService() throws Exception {
        OrderResponseDTO response = new OrderResponseDTO();
        response.setOrderId(42L);
        response.setStatus(OrderStatus.PAYMENT_PENDING);
        when(orderService.createOrder(any())).thenReturn(response);

        mockMvc.perform(post("/orders").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"));
    }

    private static String bearer() {
        Instant now = Instant.now();
        return "Bearer " + Jwts.builder()
                .subject("7")
                .claim("roles", List.of("ROLE_USER"))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
