package org.bgm.orderservice.event;

public record PaymentFailedEvent(String eventId, long orderId, String reason, String occurredAt) {
}
