package org.bgm.paymentservice.event;

// Matches common-lib's payment-failed.schema.json.
public record PaymentFailedEvent(String eventId, long orderId, String reason, String occurredAt) {
}
