package org.bgm.notificationservice.event;

// Consumer-side view of payment-service's payment-success event.
public record PaymentSuccessEvent(String eventId, long orderId) {
}
