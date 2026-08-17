package org.bgm.common.correlation;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.api.trace.TraceState;
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
    private final String previousSpanId;

    private OrderCorrelationScope(String previousCorrelationId, String previousOrderId, String previousTraceId, String previousSpanId) {
        this.previousCorrelationId = previousCorrelationId;
        this.previousOrderId = previousOrderId;
        this.previousTraceId = previousTraceId;
        this.previousSpanId = previousSpanId;
    }

    // ADR-0052: overload kept for any caller that predates traceId
    // propagation — degrades to leaving traceId unset, same as an event
    // published before this ADR shipped would.
    public static OrderCorrelationScope forOrder(long orderId, String correlationId) {
        return forOrder(orderId, correlationId, null);
    }

    // ADR-0062: overload kept for any caller that predates span-link
    // propagation — degrades to no link, same as an event published
    // before this ADR shipped would.
    public static OrderCorrelationScope forOrder(long orderId, String correlationId, String traceId) {
        return forOrder(orderId, correlationId, traceId, null);
    }

    public static OrderCorrelationScope forOrder(long orderId, String correlationId, String traceId, String parentSpanId) {
        String previousCorrelationId = MDC.get(CorrelationConstants.MDC_CORRELATION_ID_KEY);
        String previousOrderId = MDC.get(CorrelationConstants.MDC_ORDER_ID_KEY);
        String previousTraceId = MDC.get(CorrelationConstants.MDC_TRACE_ID_KEY);
        String previousSpanId = MDC.get(CorrelationConstants.MDC_SPAN_ID_KEY);
        String resolvedCorrelationId = (correlationId == null || correlationId.isBlank())
                ? String.valueOf(orderId)
                : correlationId;
        MDC.put(CorrelationConstants.MDC_CORRELATION_ID_KEY, resolvedCorrelationId);
        MDC.put(CorrelationConstants.MDC_ORDER_ID_KEY, String.valueOf(orderId));
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(CorrelationConstants.MDC_TRACE_ID_KEY, traceId);
        }
        if (parentSpanId != null && !parentSpanId.isBlank()) {
            MDC.put(CorrelationConstants.MDC_SPAN_ID_KEY, parentSpanId);
        }
        // ADR-0056: also stamp the currently active OTel span, not just
        // MDC. Found live, checking Tempo's own tag index directly: every
        // Kafka/RabbitMQ-listener-triggered service in this saga
        // (inventory-service, payment-service, notification-service)
        // sets its correlation context EXCLUSIVELY through this method,
        // never through their own inbound HTTP request — meaning
        // SpanAttributeEnrichmentFilter (a Servlet HTTP Filter) can
        // structurally never enrich their spans, no matter how correctly
        // it's deployed, because a Kafka/RabbitMQ listener invocation
        // never passes through any Servlet filter chain at all. This is
        // the one call site every one of those services' correlation
        // logic already funnels through, so it's the correct place to
        // fix it for all of them at once, rather than trying to hook
        // every listener-container/Observation-API integration
        // individually. Span.current() here resolves to whatever span
        // (if any) is active for this thread's execution — a real Kafka
        // listener span if that instrumentation is active, or a
        // harmless no-op span otherwise (setAttribute on a no-op span is
        // a documented no-op, not an error).
        Span span = Span.current();
        setSpanAttributeIfPresent(span, CorrelationConstants.MDC_CORRELATION_ID_KEY, resolvedCorrelationId);
        setSpanAttributeIfPresent(span, CorrelationConstants.MDC_ORDER_ID_KEY, String.valueOf(orderId));
        setSpanAttributeIfPresent(span, CorrelationConstants.MDC_TRACE_ID_KEY, traceId);
        // ADR-0062: link this listener's span back to the saga's
        // originating span (order-service's HTTP request span,
        // propagated unchanged through every hop — see
        // CorrelationConstants.MDC_SPAN_ID_KEY's Javadoc). Deliberately
        // Span.addLink(), not Span.setParent() / a Context-level
        // parent: a span can only have one parent, but a single
        // order-created event fans out to multiple independent
        // consumers (inventory, and later payment/notification), so
        // there is no single "child" relationship to model — this is
        // exactly the scenario OpenTelemetry's messaging semantic
        // conventions document span Links for. addLink() is valid on
        // an already-started span (no ordering requirement forcing it
        // to happen before the listener's own auto-instrumented span
        // was created), so this single shared call site can add the
        // link regardless of whether Kafka/RabbitMQ observation
        // (ADR-0057) created a real span or a no-op one is active.
        if (traceId != null && TraceId.isValid(traceId) && parentSpanId != null && SpanId.isValid(parentSpanId)) {
            SpanContext remoteOrigin = SpanContext.createFromRemoteParent(
                    traceId, parentSpanId, TraceFlags.getSampled(), TraceState.getDefault());
            span.addLink(remoteOrigin);
        }
        return new OrderCorrelationScope(previousCorrelationId, previousOrderId, previousTraceId, previousSpanId);
    }

    private static void setSpanAttributeIfPresent(Span span, String key, String value) {
        if (value != null && !value.isBlank()) {
            span.setAttribute(key, value);
        }
    }

    @Override
    public void close() {
        restore(CorrelationConstants.MDC_CORRELATION_ID_KEY, previousCorrelationId);
        restore(CorrelationConstants.MDC_ORDER_ID_KEY, previousOrderId);
        restore(CorrelationConstants.MDC_TRACE_ID_KEY, previousTraceId);
        restore(CorrelationConstants.MDC_SPAN_ID_KEY, previousSpanId);
    }

    private static void restore(String key, String previousValue) {
        if (previousValue == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previousValue);
        }
    }
}
