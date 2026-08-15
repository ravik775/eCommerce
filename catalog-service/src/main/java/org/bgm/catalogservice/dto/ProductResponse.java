package org.bgm.catalogservice.dto;

import org.bgm.catalogservice.model.Product;
import org.bgm.catalogservice.model.ProductStatus;

public record ProductResponse(
        long id,
        String name,
        String description,
        Long categoryId,
        Double price,
        boolean active,
        String providerId,
        String providerName,
        ProductStatus status
) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getCategoryId(),
                product.getPrice(),
                product.isActive(),
                product.getProviderId(),
                product.getProviderName(),
                product.getStatus()
        );
    }
}
