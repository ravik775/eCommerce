package org.bgm.common.correlation;

import org.slf4j.MDC;

/**
 * ADR-0032: sets both MDC fields for a Kafka-driven saga step —
 * {@code correlationId} from the original HTTP request that started
 * this order (propagated via a Kafka message header, see
 * {@code OrderEventPublisher}/{@code OutboxPoller} on the producer
 * side), and {@code orderId} as a separate, always-present field (a
 * genuinely useful "everything about order #23" search key in its own
 * right, not a stand-in for correlationId — see
 * {@code CorrelationConstants.MDC_ORDER_ID_KEY}'s Javadoc).
 * <p>
 * Falls back to using the order ID as the correlation ID too if no
 * header was present (an event published before this ADR shipped, or a
 * caller that bypassed the HTTP entry point) — degrades to the old
 * behavior rather than leaving correlationId blank.
 * <p>
 * try-with-resources scope, not a plain set/clear pair: guarantees MDC
 * cleanup even if the listener throws, so a failed reservation/payment
 * doesn't leak stale values onto whatever this consumer thread
 * processes next.
 */
public final class OrderCorrelationScope implements AutoCloseable {

    private final String previousCorrelationId;
    private final String previousOrderId;

    private OrderCorrelationScope(String previousCorrelationId, String previousOrderId) {
        this.previousCorrelationId = previousCorrelationId;
        this.previousOrderId = previousOrderId;
    }

    public static OrderCorrelationScope forOrder(long orderId, String correlationId) {
        String previousCorrelationId = MDC.get(CorrelationConstants.MDC_CORRELATION_ID_KEY);
        String previousOrderId = MDC.get(CorrelationConstants.MDC_ORDER_ID_KEY);
        String resolvedCorrelationId = (correlationId == null || correlationId.isBlank())
                ? String.valueOf(orderId)
                : correlationId;
        MDC.put(CorrelationConstants.MDC_CORRELATION_ID_KEY, resolvedCorrelationId);
        MDC.put(CorrelationConstants.MDC_ORDER_ID_KEY, String.valueOf(orderId));
        return new OrderCorrelationScope(previousCorrelationId, previousOrderId);
    }

    @Override
    public void close() {
        restore(CorrelationConstants.MDC_CORRELATION_ID_KEY, previousCorrelationId);
        restore(CorrelationConstants.MDC_ORDER_ID_KEY, previousOrderId);
    }

    private static void restore(String key, String previousValue) {
        if (previousValue == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previousValue);
        }
    }
}
