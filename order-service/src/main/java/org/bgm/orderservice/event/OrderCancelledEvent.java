package org.bgm.orderservice.event;

// Matches common-lib's order-cancelled.schema.json.
public record OrderCancelledEvent(String eventId, long orderId, String reason, String occurredAt) {
}
