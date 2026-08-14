package org.bgm.orderservice.event;

import java.util.List;

// Field-for-field match with common-lib's order-created.schema.json —
// validated against that schema before publishing (ADR-0015).
public record OrderCreatedEvent(
        String eventId,
        long orderId,
        String customerId,
        List<Item> items,
        double totalAmount,
        String occurredAt
) {
    public record Item(long productId, int quantity, double unitPrice) {
    }
}
