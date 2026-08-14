package org.bgm.inventoryservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record BulkInventoryRequest(
        Long orderId, // nullable: "add" may be a plain restock, not order-triggered
        @NotEmpty @Valid List<InventoryItemRequest> items
) {
}
