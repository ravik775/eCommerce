package org.bgm.inventoryservice.event;

import java.util.List;

// Matches common-lib's inventory-reserved.schema.json.
public record InventoryReservedEvent(String eventId, long orderId, List<Item> items, String occurredAt) {
    public record Item(long productId, int quantity) {
    }
}
