package org.bgm.orderservice.event;

public record InventoryReservationFailedEvent(String eventId, long orderId, String reason, String occurredAt) {
}
