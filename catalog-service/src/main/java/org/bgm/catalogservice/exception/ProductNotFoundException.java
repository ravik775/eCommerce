package org.bgm.catalogservice.exception;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(long productId) {
        super("Product not found: " + productId);
    }
}
