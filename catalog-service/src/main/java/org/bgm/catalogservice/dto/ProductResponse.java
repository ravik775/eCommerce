package org.bgm.catalogservice.dto;

import org.bgm.catalogservice.model.Product;

public record ProductResponse(
        long id,
        String name,
        String description,
        Long categoryId,
        Double price,
        boolean active
) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getCategoryId(),
                product.getPrice(),
                product.isActive()
        );
    }
}
