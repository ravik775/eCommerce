package org.bgm.orderservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.bgm.common.correlation.CorrelationConstants;
import org.bgm.common.event.schema.EventSchemaValidator;
import org.bgm.common.event.schema.EventType;
import org.bgm.orderservice.model.OutboxEvent;
import org.bgm.orderservice.repository.OutboxEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Phase 0 (outbox-deduplication refactor plan): characterization tests
 * for CURRENT behavior — no unit test existed for this class before
 * this refactor. Locks down the ROOT-CAPTURE span behavior specifically
 * (ADR-0062): order-service is always the saga's root (a live
 * synchronous HTTP request), so this must capture a FRESH
 * Span.current() on every publish call — the opposite of inventory-
 * service/payment-service's forward-from-MDC behavior (see their
 * EventPublisherTests for the contrasting case this refactor must keep
 * distinct).
 */
class OrderEventPublisherTest {

    private OutboxEventRepository repository;
    private OrderEventPublisher publisher;
    private SdkTracerProvider tracerProvider;
    private Tracer tracer;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        EventSchemaValidator validator = mock(EventSchemaValidator.class);
        publisher = new OrderEventPublisher(repository, validator, new ObjectMapper());
        tracerProvider = SdkTracerProvider.builder().build();
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
    void capturesFreshSpanIdFromTheCurrentlyActiveSpan() {
        Span span = tracer.spanBuilder("http post /order").startSpan();
        String expectedSpanId;
        try (Scope ignored = span.makeCurrent()) {
            expectedSpanId = span.getSpanContext().getSpanId();
            publisher.publish(EventType.ORDER_CREATED, 42L, "evt-1", new Object());
        } finally {
            span.end();
        }

        var captor = org.mockito.ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        assertEquals(expectedSpanId, captor.getValue().getSpanId());
        assertTrue(SpanId.isValid(captor.getValue().getSpanId()));
    }

    @Test
    void leavesSpanIdNullWhenNoActiveSpanNeverReadsMdcForIt() {
        // No active span (matches an uninstrumented call path) AND MDC
        // has a span-id key set (simulating stale data left by some
        // other code path) — must still leave it null, proving this
        // never falls back to MDC the way inventory/payment-service do.
        MDC.put(CorrelationConstants.MDC_SPAN_ID_KEY, "should-never-be-read");

        publisher.publish(EventType.ORDER_CREATED, 43L, "evt-2", new Object());

        var captor = org.mockito.ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        assertNull(captor.getValue().getSpanId());
    }

    @Test
    void copiesCorrelationAndTraceIdFromMdcOntoTheSavedEvent() {
        MDC.put(CorrelationConstants.MDC_CORRELATION_ID_KEY, "corr-1");
        MDC.put(CorrelationConstants.MDC_TRACE_ID_KEY, "trace-1");

        publisher.publish(EventType.ORDER_CREATED, 44L, "evt-3", new Object());

        var captor = org.mockito.ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        OutboxEvent saved = captor.getValue();
        assertEquals("corr-1", saved.getCorrelationId());
        assertEquals("trace-1", saved.getTraceId());
        assertFalse(saved.isPublished());
    }

    @Test
    void validatesPayloadAgainstEventSchemaBeforeSaving() {
        EventSchemaValidator validator = mock(EventSchemaValidator.class);
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        OrderEventPublisher pub = new OrderEventPublisher(repo, validator, new ObjectMapper());

        pub.publish(EventType.ORDER_CREATED, 45L, "evt-4", new Object());

        verify(validator).validate(org.mockito.ArgumentMatchers.eq(EventType.ORDER_CREATED), any(com.fasterxml.jackson.databind.JsonNode.class));
    }

    @Test
    void serializesPayloadAsJsonOntoTheEvent() {
        record Item(String name) {}
        publisher.publish(EventType.ORDER_CREATED, 46L, "evt-5", new Item("widget"));

        var captor = org.mockito.ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        assertEquals("{\"name\":\"widget\"}", captor.getValue().getPayload());
    }
}
