package org.bgm.catalogservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record ProductRequest(
        @NotBlank String name,
        String description,
        Long categoryId,
        @NotNull @Positive Double price,
        // Only meaningful on create — ignored on update (inventory
        // adjustments go through inventory-service's own /inventory/add
        // directly, not through re-submitting a product edit).
        @PositiveOrZero Integer quantity
) {
}
