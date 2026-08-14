package org.bgm.inventoryservice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record InventoryItemRequest(
        @NotNull Long productId,
        @Positive int quantity
) {
}
