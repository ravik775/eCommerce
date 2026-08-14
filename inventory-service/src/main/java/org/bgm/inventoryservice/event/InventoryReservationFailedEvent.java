package org.bgm.inventoryservice.event;

// Matches common-lib's inventory-reservation-failed.schema.json.
public record InventoryReservationFailedEvent(String eventId, long orderId, String reason, String occurredAt) {
}
