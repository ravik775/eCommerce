package org.bgm.orderservice.event;

import java.util.List;

// Matches common-lib's order-returned.schema.json.
public record OrderReturnedEvent(String eventId, long orderId, List<Item> items, String occurredAt) {
    public record Item(long productId, int quantity) {
    }
}
