package org.bgm.common.correlation;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ADR-0056: Tempo's own tag index (GET /api/search/tags) showed zero
 * correlationId/orderId/appTraceId entries for inventory-service,
 * payment-service, and notification-service, despite order-service
 * already having them — traced to every one of those services setting
 * correlation context exclusively through this class, on Kafka/RabbitMQ
 * listener threads that never pass through the Servlet HTTP filter chain
 * SpanAttributeEnrichmentFilter depends on. This is the one call site all
 * of them share, so it's the fix point for all of them at once. Same
 * InMemorySpanExporter pattern as SpanAttributeEnrichmentFilterTest, to
 * verify against real recorded span attributes, not just "didn't throw."
 */
class OrderCorrelationScopeTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private Tracer tracer;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetry otel = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
        tracer = otel.getTracer("test");
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
        MDC.clear();
    }

    @Test
    void stampsCorrelationIdOrderIdAndAppTraceIdOntoTheActiveSpan() {
        SpanData span = runWithinSpan(() -> {
            try (var ignored = OrderCorrelationScope.forOrder(66L, "corr-123", "trace-abc")) {
                // simulates consumer business logic running inside the scope
            }
        });

        assertEquals("corr-123", span.getAttributes().get(AttributeKey.stringKey("correlationId")));
        assertEquals("66", span.getAttributes().get(AttributeKey.stringKey("orderId")));
        assertEquals("trace-abc", span.getAttributes().get(AttributeKey.stringKey("appTraceId")));
    }

    @Test
    void omitsAppTraceIdAttributeWhenTraceIdWasNotPropagated() {
        SpanData span = runWithinSpan(() -> {
            try (var ignored = OrderCorrelationScope.forOrder(67L, "corr-456", null)) {
                // no traceId header on the inbound Kafka message
            }
        });

        assertEquals("corr-456", span.getAttributes().get(AttributeKey.stringKey("correlationId")));
        assertEquals("67", span.getAttributes().get(AttributeKey.stringKey("orderId")));
        assertNull(span.getAttributes().get(AttributeKey.stringKey("appTraceId")));
    }

    @Test
    void degradesCorrelationIdToOrderIdWhenNoneWasPropagated() {
        SpanData span = runWithinSpan(() -> {
            try (var ignored = OrderCorrelationScope.forOrder(68L, null, null)) {
                // an event published before correlation-id propagation existed
            }
        });

        assertEquals("68", span.getAttributes().get(AttributeKey.stringKey("correlationId")));
        assertEquals("68", span.getAttributes().get(AttributeKey.stringKey("orderId")));
    }

    @Test
    void settingAttributesIsHarmlessWithNoActiveSpan() {
        // No tracer.spanBuilder(...).startSpan() wrapping here at all —
        // Span.current() resolves to OTel's own no-op span, exactly the
        // situation a Kafka/RabbitMQ listener with no tracing
        // instrumentation active would be in. Must not throw.
        try (var ignored = OrderCorrelationScope.forOrder(69L, "corr-789", "trace-def")) {
            assertEquals("corr-789", MDC.get(CorrelationConstants.MDC_CORRELATION_ID_KEY));
        }
    }

    @Test
    void addsSpanLinkToOriginWhenValidTraceIdAndParentSpanIdPropagated() {
        // ADR-0062: 32-hex traceId / 16-hex spanId — genuine OTel ID
        // shapes, not placeholders, since TraceId.isValid()/SpanId.isValid()
        // reject anything else (e.g. the "trace-abc" values the other
        // tests above use, which is why this needs its own real IDs).
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String parentSpanId = "00f067aa0ba902b7";
        SpanData span = runWithinSpan(() -> {
            try (var ignored = OrderCorrelationScope.forOrder(70L, "corr-999", traceId, parentSpanId)) {
                // simulates a downstream saga consumer's listener span
            }
        });

        assertEquals(1, span.getLinks().size());
        assertEquals(traceId, span.getLinks().get(0).getSpanContext().getTraceId());
        assertEquals(parentSpanId, span.getLinks().get(0).getSpanContext().getSpanId());
    }

    @Test
    void addsNoSpanLinkWhenParentSpanIdIsAbsent() {
        SpanData span = runWithinSpan(() -> {
            try (var ignored = OrderCorrelationScope.forOrder(71L, "corr-111", "4bf92f3577b34da6a3ce929d0e0e4736")) {
                // an event published before span-link propagation existed
            }
        });

        assertEquals(0, span.getLinks().size());
    }

    @Test
    void addsNoSpanLinkWhenParentSpanIdIsMalformed() {
        SpanData span = runWithinSpan(() -> {
            try (var ignored = OrderCorrelationScope.forOrder(
                    72L, "corr-222", "4bf92f3577b34da6a3ce929d0e0e4736", "not-a-real-span-id")) {
                // defensive: a malformed/legacy header value must not crash
            }
        });

        assertEquals(0, span.getLinks().size());
    }

    private interface ScopedAction {
        void run();
    }

    private SpanData runWithinSpan(ScopedAction action) {
        Span span = tracer.spanBuilder("test-span").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            action.run();
        } finally {
            span.end();
        }
        List<SpanData> spans = exporter.getFinishedSpanItems();
        return spans.get(spans.size() - 1);
    }
}
