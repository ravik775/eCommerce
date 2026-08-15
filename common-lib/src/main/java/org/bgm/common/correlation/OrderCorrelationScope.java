package org.bgm.common.correlation;

import org.slf4j.MDC;

/**
 * Uses the order ID itself as the correlation ID for Kafka-driven saga
 * steps (order-service, inventory-service, payment-service) — the HTTP
 * correlation ID (ADR-0023) doesn't survive a Kafka hop (no header
 * propagation into event payloads), so every consumer previously
 * generated its own random one independently, making a single order's
 * saga impossible to grep as one correlationId across services. The
 * order ID is already the one identifier every step of the saga
 * genuinely shares, so it's what actually joins the log lines together.
 * <p>
 * try-with-resources scope, not a plain set/clear pair: guarantees MDC
 * cleanup even if the listener throws, so a failed reservation/payment
 * doesn't leak a stale correlationId onto whatever this consumer thread
 * processes next.
 */
public final class OrderCorrelationScope implements AutoCloseable {

    private final String previous;

    private OrderCorrelationScope(String previous) {
        this.previous = previous;
    }

    public static OrderCorrelationScope forOrder(long orderId) {
        String previous = MDC.get(CorrelationConstants.MDC_CORRELATION_ID_KEY);
        MDC.put(CorrelationConstants.MDC_CORRELATION_ID_KEY, String.valueOf(orderId));
        return new OrderCorrelationScope(previous);
    }

    @Override
    public void close() {
        if (previous == null) {
            MDC.remove(CorrelationConstants.MDC_CORRELATION_ID_KEY);
        } else {
            MDC.put(CorrelationConstants.MDC_CORRELATION_ID_KEY, previous);
        }
    }
}
