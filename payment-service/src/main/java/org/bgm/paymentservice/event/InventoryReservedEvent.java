package org.bgm.paymentservice.event;

// Consumer-side view of inventory-service's inventory-reserved event —
// only orderId is needed here; amount isn't in this event (that's
// order-service's domain, not inventory's) so payment-service fetches it
// via OrderServiceClient.
public record InventoryReservedEvent(String eventId, long orderId) {
}
