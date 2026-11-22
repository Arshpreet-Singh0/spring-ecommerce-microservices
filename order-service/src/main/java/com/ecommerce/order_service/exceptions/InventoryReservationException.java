package com.ecommerce.order_service.exceptions;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Inventory could not be reserved for an order. Carries the HTTP status to return to the
 * client: 409 (out of stock), 404 (unknown SKU) or 503 (inventory-service unavailable).
 */
@Getter
public class InventoryReservationException extends RuntimeException {

    private final HttpStatus status;

    public InventoryReservationException(String msg, HttpStatus status) {
        super(msg);
        this.status = status;
    }
}
