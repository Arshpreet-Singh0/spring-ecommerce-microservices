package com.ecommerce.inventory_service.controllers;

import com.ecommerce.inventory_service.config.WebSecurityConfig;
import com.ecommerce.inventory_service.filters.JwtAuthFilter;
import com.ecommerce.inventory_service.security.JwtAccessDeniedHandler;
import com.ecommerce.inventory_service.security.JwtAuthenticationEntryPoint;
import com.ecommerce.inventory_service.services.InventoryReservationFacade;
import com.ecommerce.inventory_service.services.InventoryService;
import com.ecommerce.inventory_service.services.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InventoryController.class,
        properties = "jwt.secretKey=" + InventoryControllerWebMvcTest.SECRET)
@Import({WebSecurityConfig.class, JwtAuthFilter.class, JwtService.class,
        JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        InventoryControllerWebMvcTest.ClockConfig.class})
class InventoryControllerWebMvcTest {

    static final String SECRET = "webmvc-test-secret-key-at-least-32-bytes";

    /** AppConfig also enables @Retryable; only the Clock is needed here. */
    @TestConfiguration
    static class ClockConfig {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InventoryService inventoryService;
    @MockitoBean
    private InventoryReservationFacade reservationFacade;

    @Test
    void updateInventory_withUserOnlyToken_is403() throws Exception {
        mockMvc.perform(put("/inventory/update").header("Authorization", bearer("ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sku\": \"SKU-A\", \"quantity\": 5}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(inventoryService);
    }

    @Test
    void updateInventory_withoutToken_is401() throws Exception {
        mockMvc.perform(put("/inventory/update")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sku\": \"SKU-A\", \"quantity\": 5}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateInventory_asAdmin_isOk() throws Exception {
        mockMvc.perform(put("/inventory/update").header("Authorization", bearer("ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sku\": \"SKU-A\", \"quantity\": 5}"))
                .andExpect(status().isOk());
        verify(inventoryService).updateInventory(any());
    }

    @Test
    void reserve_withInvalidBody_is400() throws Exception {
        mockMvc.perform(post("/inventory/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\": \"\", \"items\": [{\"sku\": \"SKU-A\", \"qty\": 0}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.apiError.subErrors", hasItem("orderId is required")))
                .andExpect(jsonPath("$.apiError.subErrors", hasItem("qty must be positive")));
        verifyNoInteractions(reservationFacade);
    }

    @Test
    void reserve_withValidBody_goesThroughTheRetryingFacade() throws Exception {
        mockMvc.perform(post("/inventory/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\": \"42\", \"items\": [{\"sku\": \"SKU-A\", \"qty\": 1}]}"))
                .andExpect(status().isOk());
        verify(reservationFacade).reserveInventory(any());
    }

    private static String bearer(String... roles) {
        Instant now = Instant.now();
        return "Bearer " + Jwts.builder()
                .subject("7")
                .claim("roles", List.of(roles))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
