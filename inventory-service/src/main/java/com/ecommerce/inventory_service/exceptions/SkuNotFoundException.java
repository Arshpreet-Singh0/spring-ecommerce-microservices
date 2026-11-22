package com.ecommerce.inventory_service.exceptions;

/** Requested SKU has no inventory record. Mapped to HTTP 404. */
public class SkuNotFoundException extends RuntimeException {
    public SkuNotFoundException(String sku) {
        super("SKU not found: " + sku);
    }
}
