package org.bgm.notificationservice.dispatch;

// The RabbitMQ work-item payload — decoupled from the Kafka event shape on
// purpose: "an event happened" (Kafka) vs. "send this one notification,
// retry on failure" (RabbitMQ) are different concerns (ADR-0003).
public record NotificationDispatchMessage(long orderId, String type, String occurredAt) {
    public static final String TYPE_ORDER_CONFIRMATION = "ORDER_CONFIRMATION";
    public static final String TYPE_PAYMENT_FAILED = "PAYMENT_FAILED";
}
