package org.bgm.orderservice.event;

public record PaymentSuccessEvent(String eventId, long orderId, String paymentId, double amount, String occurredAt) {
}
