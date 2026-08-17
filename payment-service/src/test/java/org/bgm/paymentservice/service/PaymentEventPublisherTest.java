package org.bgm.paymentservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bgm.common.correlation.CorrelationConstants;
import org.bgm.common.event.schema.EventSchemaValidator;
import org.bgm.common.event.schema.EventType;
import org.bgm.paymentservice.model.OutboxEvent;
import org.bgm.paymentservice.repository.OutboxEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Phase 0 (outbox-deduplication refactor plan): characterization tests
 * for CURRENT behavior — mirrors inventory-service's
 * InventoryEventPublisherTest (identical forward-from-MDC span
 * behavior, ADR-0062) since payment-service is likewise always a
 * downstream saga hop, never the root.
 */
class PaymentEventPublisherTest {

    private OutboxEventRepository repository;
    private PaymentEventPublisher publisher;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        EventSchemaValidator validator = mock(EventSchemaValidator.class);
        publisher = new PaymentEventPublisher(repository, validator, new ObjectMapper());
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void copiesCorrelationTraceAndSpanIdFromMdcOntoTheSavedEvent() {
        MDC.put(CorrelationConstants.MDC_CORRELATION_ID_KEY, "corr-1");
        MDC.put(CorrelationConstants.MDC_TRACE_ID_KEY, "trace-1");
        MDC.put(CorrelationConstants.MDC_SPAN_ID_KEY, "span-1");

        publisher.publish(EventType.PAYMENT_SUCCESS, 42L, "evt-1", new Object());

        var captor = org.mockito.ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        OutboxEvent saved = captor.getValue();
        assertEquals("corr-1", saved.getCorrelationId());
        assertEquals("trace-1", saved.getTraceId());
        assertEquals("span-1", saved.getSpanId());
        assertFalse(saved.isPublished());
    }

    @Test
    void leavesSpanIdNullWhenNotPropagatedNeverCapturesItsOwnSpan() {
        publisher.publish(EventType.PAYMENT_SUCCESS, 43L, "evt-2", new Object());

        var captor = org.mockito.ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        assertNull(captor.getValue().getSpanId());
    }

    @Test
    void validatesPayloadAgainstEventSchemaBeforeSaving() {
        EventSchemaValidator validator = mock(EventSchemaValidator.class);
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        PaymentEventPublisher pub = new PaymentEventPublisher(repo, validator, new ObjectMapper());

        pub.publish(EventType.PAYMENT_SUCCESS, 44L, "evt-3", new Object());

        verify(validator).validate(org.mockito.ArgumentMatchers.eq(EventType.PAYMENT_SUCCESS), any(com.fasterxml.jackson.databind.JsonNode.class));
    }

    @Test
    void serializesPayloadAsJsonOntoTheEvent() {
        record Item(String name) {}
        publisher.publish(EventType.PAYMENT_SUCCESS, 45L, "evt-4", new Item("widget"));

        var captor = org.mockito.ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        assertEquals("{\"name\":\"widget\"}", captor.getValue().getPayload());
    }
}
