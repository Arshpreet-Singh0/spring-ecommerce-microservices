package com.ecommerce.inventory_service.exceptions;

/** Not enough available stock to satisfy a reservation. Mapped to HTTP 409. */
public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String sku, int requested, int available) {
        super("Insufficient inventory for SKU: " + sku + " (requested " + requested + ", available " + available + ")");
    }
}
