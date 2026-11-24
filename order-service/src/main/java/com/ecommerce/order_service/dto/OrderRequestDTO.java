package com.ecommerce.order_service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class OrderRequestDTO {

    private BigDecimal totalAmount;

    @NotBlank(message = "idempotencyKey is required")
    private String idempotencyKey;

    @NotEmpty(message = "items must not be empty")
    private List<@Valid OrderItemDTO> items;
}
