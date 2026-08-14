package org.bgm.inventoryservice.event;

import java.util.List;

// Consumer-side view of order-service's order-created event — matches
// common-lib's order-created.schema.json. Only the fields inventory-service
// actually needs are modeled.
public record OrderCreatedEvent(String eventId, long orderId, List<Item> items) {
    public record Item(long productId, int quantity) {
    }
}
