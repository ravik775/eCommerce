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
    // ADR-0052 (2026-08-17 incident correction): was "traceId" — a real,
    // live-reproduced MDC key collision with Micrometer Tracing's own
    // MDCScopeDecorator (common-lib depends on micrometer-tracing-bridge-otel),
    // which automatically manages MDC["traceId"] itself, tied to the
    // REAL OTel span context, activated on every auto-instrumented
    // Observation scope (e.g. an outbound RestTemplate call). Found live:
    // payment-service's X-Trace-Id was correctly present in MDC right up
    // until its one synchronous outbound call (OrderServiceClient — the
    // saga's only cross-service HTTP call, ADR-0007) — logging inside
    // that call bisected the exact loss point to inside
    // restTemplate.exchange() itself. Micrometer's own scope push/pop
    // around that call clobbered our key, since it owns the SAME MDC
    // key name for a completely different purpose (the real OTel trace,
    // which this project has always deliberately kept distinct from
    // this application-level X-Trace-Id — see ADR-0023). This collision
    // was latent since ADR-0032 first added Micrometer Tracing — it
    // simply had no way to manifest until ADR-0052 started actually
    // writing an application value into MDC["traceId"] (the old
    // correlationId-only propagation never touched this key at all).
    public static final String MDC_TRACE_ID_KEY = "appTraceId";

    // ADR-0032: the order ID is a separate, additional log field, not a
    // substitute for correlationId — the original correlation ID now
    // survives front-to-back across the whole saga (via the outbox row +
    // Kafka header, see OrderEventPublisher/OutboxPoller), so order ID
    // no longer needs to stand in for it. Kept because "find every log
    // line about order #23" is still a genuinely useful, distinct query.
    public static final String MDC_ORDER_ID_KEY = "orderId";

    // ADR-0062: the real OTel span ID of the request/consume invocation
    // that originated this saga (order-service's HTTP request span,
    // captured once and forwarded unchanged through every downstream
    // hop — never re-captured mid-saga). Not printed by the logging
    // pattern (it's not useful to a human reading logs, unlike the
    // other three keys) — MDC is reused here purely as the same
    // proven propagation vehicle (outbox column -> Kafka/Rabbit header
    // -> MDC restore) already trusted for correlationId/traceId, so
    // OrderCorrelationScope can link the current span back to it in
    // one place instead of threading a fifth method parameter through
    // every publisher/consumer by hand.
    public static final String MDC_SPAN_ID_KEY = "parentSpanId";

    private CorrelationConstants() {
    }
}
