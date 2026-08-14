package org.bgm.inventoryservice.dto;

import org.bgm.inventoryservice.model.Inventory;

public record InventoryResponse(long productId, int availableQty, int reservedQty) {
    public static InventoryResponse from(Inventory inventory) {
        return new InventoryResponse(inventory.getProductId(), inventory.getAvailableQty(), inventory.getReservedQty());
    }
}
