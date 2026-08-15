package org.bgm.common.correlation;

/**
 * ADR-0023 (doc/adr/ADR-0023-correlation-trace-id.md): correlation ID and
 * trace ID are distinct headers with distinct uniqueness guarantees — see
 * the ADR for why they are not the same value.
 */
public final class CorrelationConstants {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    public static final String MDC_CORRELATION_ID_KEY = "correlationId";
    public static final String MDC_TRACE_ID_KEY = "traceId";

    // ADR-0032: the order ID is a separate, additional log field, not a
    // substitute for correlationId — the original correlation ID now
    // survives front-to-back across the whole saga (via the outbox row +
    // Kafka header, see OrderEventPublisher/OutboxPoller), so order ID
    // no longer needs to stand in for it. Kept because "find every log
    // line about order #23" is still a genuinely useful, distinct query.
    public static final String MDC_ORDER_ID_KEY = "orderId";

    private CorrelationConstants() {
    }
}
