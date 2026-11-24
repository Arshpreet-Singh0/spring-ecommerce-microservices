package com.ecommerce.inventory_service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.List;

@Data
public class ReserveInventoryDTO {

    @NotBlank(message = "orderId is required")
    private String orderId;

    @NotEmpty(message = "items must not be empty")
    private List<@Valid Item> items;

    @Data
    public static class Item {

        @NotBlank(message = "sku is required")
        private String sku;

        @Positive(message = "qty must be positive")
        private int qty;
    }
}
