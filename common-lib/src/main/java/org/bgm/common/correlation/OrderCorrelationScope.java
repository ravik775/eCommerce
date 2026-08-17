package org.bgm.common.correlation;

import org.slf4j.MDC;

/**
 * ADR-0032: sets the MDC fields for a Kafka-driven saga step —
 * {@code correlationId} from the original HTTP request that started
 * this order (propagated via a Kafka message header, see
 * {@code OrderEventPublisher}/{@code OutboxPoller} on the producer
 * side), and {@code orderId} as a separate, always-present field (a
 * genuinely useful "everything about order #23" search key in its own
 * right, not a stand-in for correlationId — see
 * {@code CorrelationConstants.MDC_ORDER_ID_KEY}'s Javadoc).
 * <p>
 * ADR-0052 (2026-08-17): {@code traceId} now also propagates the same
 * way — set on the outbox row at publish time, carried as a Kafka
 * message header, restored into MDC here on consume. This is a
 * deliberately separate concern from the gateway's own real OTel span
 * tree (which still only spans the synchronous HTTP hops, see
 * ForceTraceFilter's Javadoc for that documented scope boundary): this
 * is the same gateway-generated {@code X-Trace-Id} log-correlation
 * value flowing as a plain field across every hop of the saga,
 * including the async Kafka ones, so "find every log line for this
 * user action" works end to end without needing Tempo's span-tree
 * hierarchy to also extend there.
 * <p>
 * Falls back to using the order ID as the correlation ID too if no
 * header was present (an event published before this ADR shipped, or a
 * caller that bypassed the HTTP entry point) — degrades to the old
 * behavior rather than leaving correlationId blank. No equivalent
 * fallback exists for traceId — it's simply left unset (not synthesized)
 * when absent, since unlike correlationId it was never treated as a
 * mandatory field before this update.
 * <p>
 * try-with-resources scope, not a plain set/clear pair: guarantees MDC
 * cleanup even if the listener throws, so a failed reservation/payment
 * doesn't leak stale values onto whatever this consumer thread
 * processes next.
 */
public final class OrderCorrelationScope implements AutoCloseable {

    private final String previousCorrelationId;
    private final String previousOrderId;
    private final String previousTraceId;

    private OrderCorrelationScope(String previousCorrelationId, String previousOrderId, String previousTraceId) {
        this.previousCorrelationId = previousCorrelationId;
        this.previousOrderId = previousOrderId;
        this.previousTraceId = previousTraceId;
    }

    // ADR-0052: overload kept for any caller that predates traceId
    // propagation — degrades to leaving traceId unset, same as an event
    // published before this ADR shipped would.
    public static OrderCorrelationScope forOrder(long orderId, String correlationId) {
        return forOrder(orderId, correlationId, null);
    }

    public static OrderCorrelationScope forOrder(long orderId, String correlationId, String traceId) {
        String previousCorrelationId = MDC.get(CorrelationConstants.MDC_CORRELATION_ID_KEY);
        String previousOrderId = MDC.get(CorrelationConstants.MDC_ORDER_ID_KEY);
        String previousTraceId = MDC.get(CorrelationConstants.MDC_TRACE_ID_KEY);
        String resolvedCorrelationId = (correlationId == null || correlationId.isBlank())
                ? String.valueOf(orderId)
                : correlationId;
        MDC.put(CorrelationConstants.MDC_CORRELATION_ID_KEY, resolvedCorrelationId);
        MDC.put(CorrelationConstants.MDC_ORDER_ID_KEY, String.valueOf(orderId));
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(CorrelationConstants.MDC_TRACE_ID_KEY, traceId);
        }
        return new OrderCorrelationScope(previousCorrelationId, previousOrderId, previousTraceId);
    }

    @Override
    public void close() {
        restore(CorrelationConstants.MDC_CORRELATION_ID_KEY, previousCorrelationId);
        restore(CorrelationConstants.MDC_ORDER_ID_KEY, previousOrderId);
        restore(CorrelationConstants.MDC_TRACE_ID_KEY, previousTraceId);
    }

    private static void restore(String key, String previousValue) {
        if (previousValue == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previousValue);
        }
    }
}
