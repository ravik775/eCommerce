package org.bgm.paymentservice.event;

// Matches common-lib's payment-success.schema.json.
public record PaymentSuccessEvent(String eventId, long orderId, String paymentId, double amount, String occurredAt) {
}
