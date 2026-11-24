package com.ecommerce.product_service.controllers;

import com.ecommerce.product_service.configs.AppConfig;
import com.ecommerce.product_service.configs.WebSecurityConfig;
import com.ecommerce.product_service.dto.ProductResponseDTO;
import com.ecommerce.product_service.filters.JwtAuthFilter;
import com.ecommerce.product_service.security.JwtAccessDeniedHandler;
import com.ecommerce.product_service.security.JwtAuthenticationEntryPoint;
import com.ecommerce.product_service.services.JwtService;
import com.ecommerce.product_service.services.ProductService;
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

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ProductController.class, properties = "jwt.secretKey=" + ProductControllerWebMvcTest.SECRET)
@Import({WebSecurityConfig.class, JwtAuthFilter.class, JwtService.class,
        JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class, AppConfig.class})
class ProductControllerWebMvcTest {

    static final String SECRET = "webmvc-test-secret-key-at-least-32-bytes";

    private static final String VALID_BODY = """
            {"name": "Trail runner", "description": "Lightweight", "sku": "TRAIL-01", "price": 120.00,
             "currency": "USD", "category": "shoes", "brand": "Acme", "totalQuantity": 5,
             "imageUrls": ["https://img/1.png"]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @Test
    void createProduct_withoutToken_is401() throws Exception {
        mockMvc.perform(post("/product").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(productService);
    }

    @Test
    void createProduct_withInvalidToken_is401() throws Exception {
        mockMvc.perform(post("/product").header("Authorization", "Bearer not-a-jwt")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createProduct_withUserOnlyToken_is403() throws Exception {
        mockMvc.perform(post("/product").header("Authorization", bearer("ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(productService);
    }

    @Test
    void createProduct_asAdmin_withInvalidBody_is400WithFieldErrors() throws Exception {
        String invalid = VALID_BODY.replace("\"TRAIL-01\"", "\"bad sku!\"").replace("120.00", "-5");

        mockMvc.perform(post("/product").header("Authorization", bearer("ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.apiError.status").value("400 BAD_REQUEST"))
                .andExpect(jsonPath("$.apiError.subErrors", hasSize(greaterThanOrEqualTo(2))));
        verifyNoInteractions(productService);
    }

    @Test
    void createProduct_asAdmin_withValidBody_is201() throws Exception {
        when(productService.create(any())).thenReturn(ProductResponseDTO.builder().id(1L).sku("TRAIL-01").build());

        mockMvc.perform(post("/product").header("Authorization", bearer("ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sku").value("TRAIL-01"));
    }

    @Test
    void getProductsByIds_isPublic() throws Exception {
        when(productService.findAllByIds(List.of(1L))).thenReturn(List.of(ProductResponseDTO.builder().id(1L).build()));

        mockMvc.perform(post("/product/get").contentType(MediaType.APPLICATION_JSON).content("[1]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1));
    }

    /** Mints a token the way user-service does, but with chosen roles (user-service gives everyone ADMIN today). */
    static String bearer(String... roles) {
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
