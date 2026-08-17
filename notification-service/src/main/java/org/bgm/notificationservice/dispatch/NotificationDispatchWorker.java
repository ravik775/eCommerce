package org.bgm.notificationservice.dispatch;

import lombok.extern.slf4j.Slf4j;
import org.bgm.common.correlation.CorrelationConstants;
import org.bgm.common.correlation.OrderCorrelationScope;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
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
 * <p>
 * Found live: this listener runs on a RabbitMQ-managed thread, not the
 * Kafka-consumer thread NotificationEventConsumer used to build the
 * correlation context — MDC is thread-local, so its "Dispatched
 * notification" log line always showed blank correlationId/orderId/
 * appTraceId, even after ADR-0052/ADR-0053, regardless of whether
 * upstream propagation worked. Fixed the same way as the Kafka hop:
 * NotificationEventConsumer.dispatch() now carries those three fields as
 * RabbitMQ message headers, read back here via {@code @Header} and
 * restored into MDC via the same {@link OrderCorrelationScope} every
 * other saga step already uses — no new propagation mechanism invented.
 */
@Component
@Slf4j
public class NotificationDispatchWorker {

    @RabbitListener(queues = "${notification.rabbitmq.dispatch-queue:notification.dispatch}",
            containerFactory = "dispatchListenerContainerFactory")
    public void handle(
            NotificationDispatchMessage message,
            @Header(value = CorrelationConstants.MDC_CORRELATION_ID_KEY, required = false) String correlationId,
            @Header(value = CorrelationConstants.MDC_TRACE_ID_KEY, required = false) String traceId,
            @Header(value = CorrelationConstants.MDC_SPAN_ID_KEY, required = false) String parentSpanId) {
        try (var ignored = OrderCorrelationScope.forOrder(message.orderId(), correlationId, traceId, parentSpanId)) {
            if (message.orderId() < 0) {
                throw new IllegalStateException(
                        "Simulated permanent dispatch failure for order " + message.orderId() + " (test hook)");
            }
            log.info("Dispatched notification: order={} type={} occurredAt={}",
                    message.orderId(), message.type(), message.occurredAt());
        }
    }
}
