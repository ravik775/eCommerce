package org.bgm.notificationservice.dispatch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * ADR-0003: drains notification.dispatch. "Sending" is simulated (logged)
 * — no real email/SMS gateway integration in this phase, same documented
 * scope decision as payment-service's simulated processors.
 *
 * Test hook: a negative orderId can never occur from a real order (DB
 * IDENTITY generation starts at 1) — used deliberately to prove the
 * retry-then-DLQ path actually works (Phase 3 DoD), not left as an
 * unverified claim. Not a business rule.
 */
@Component
@Slf4j
public class NotificationDispatchWorker {

    @RabbitListener(queues = "${notification.rabbitmq.dispatch-queue:notification.dispatch}",
            containerFactory = "dispatchListenerContainerFactory")
    public void handle(NotificationDispatchMessage message) {
        if (message.orderId() < 0) {
            throw new IllegalStateException(
                    "Simulated permanent dispatch failure for order " + message.orderId() + " (test hook)");
        }
        log.info("Dispatched notification: order={} type={} occurredAt={}",
                message.orderId(), message.type(), message.occurredAt());
    }
}
