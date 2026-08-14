package org.bgm.inventoryservice.exception;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(long productId) {
        super("No inventory record for product: " + productId);
    }
}
