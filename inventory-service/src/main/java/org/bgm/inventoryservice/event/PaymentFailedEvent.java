package org.bgm.inventoryservice.event;

// Consumer-side view of payment-service's payment-failed event.
public record PaymentFailedEvent(String eventId, long orderId, String reason) {
}
