package org.bgm.inventoryservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bgm.common.correlation.CorrelationConstants;
import org.bgm.common.event.schema.EventSchemaValidator;
import org.bgm.common.event.schema.EventType;
import org.bgm.inventoryservice.model.OutboxEvent;
import org.bgm.inventoryservice.repository.OutboxEventRepository;
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
 * for CURRENT behavior — no unit test existed for this class before
 * this refactor. Locks down the forward-from-MDC span behavior
 * specifically (ADR-0062): inventory-service is always a downstream
 * saga hop, never the root, so this must NEVER capture Span.current()
 * itself — see order-service's OrderEventPublisherTest for the
 * contrasting root-capture behavior this refactor must keep distinct.
 */
class InventoryEventPublisherTest {

    private OutboxEventRepository repository;
    private InventoryEventPublisher publisher;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        EventSchemaValidator validator = mock(EventSchemaValidator.class);
        publisher = new InventoryEventPublisher(repository, validator, new ObjectMapper());
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

        publisher.publish(EventType.INVENTORY_RESERVED, 42L, "evt-1", new Object());

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
        // No MDC_SPAN_ID_KEY set — simulates an event published before
        // ADR-0062 shipped, or a consumer with no propagated origin.
        // Must stay null, not fall back to Span.current() (that
        // fallback is order-service-only behavior, ADR-0062).
        publisher.publish(EventType.INVENTORY_RESERVED, 43L, "evt-2", new Object());

        var captor = org.mockito.ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        assertNull(captor.getValue().getSpanId());
    }

    @Test
    void validatesPayloadAgainstEventSchemaBeforeSaving() {
        EventSchemaValidator validator = mock(EventSchemaValidator.class);
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        InventoryEventPublisher pub = new InventoryEventPublisher(repo, validator, new ObjectMapper());

        pub.publish(EventType.INVENTORY_RESERVED, 44L, "evt-3", new Object());

        verify(validator).validate(org.mockito.ArgumentMatchers.eq(EventType.INVENTORY_RESERVED), any(com.fasterxml.jackson.databind.JsonNode.class));
    }

    @Test
    void serializesPayloadAsJsonOntoTheEvent() {
        record Item(String name) {}
        publisher.publish(EventType.INVENTORY_RESERVED, 45L, "evt-4", new Item("widget"));

        var captor = org.mockito.ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        assertEquals("{\"name\":\"widget\"}", captor.getValue().getPayload());
    }
}
