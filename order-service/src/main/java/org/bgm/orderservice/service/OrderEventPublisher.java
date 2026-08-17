package org.bgm.orderservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import lombok.RequiredArgsConstructor;
import org.bgm.common.correlation.CorrelationConstants;
import org.bgm.common.event.schema.EventSchemaValidator;
import org.bgm.common.event.schema.EventType;
import org.bgm.orderservice.model.OutboxEvent;
import org.bgm.orderservice.repository.OutboxEventRepository;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * ADR-0007 (doc/adr/ADR-0007-saga-outbox-idempotency.md): writes an
 * outbox row — never publishes to Kafka directly. Propagation.MANDATORY
 * enforces the ADR's core guarantee at compile-time-adjacent runtime
 * check: this method must be called from within an existing transaction
 * (the same one as the business write it accompanies), or it fails fast
 * rather than silently publishing outside that transaction.
 */
@Service
@RequiredArgsConstructor
public class OrderEventPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final EventSchemaValidator eventSchemaValidator;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(EventType eventType, long aggregateId, String eventId, Object payload) {
        eventSchemaValidator.validate(eventType, objectMapper.valueToTree(payload));

        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setEventType(eventType.topic());
        event.setAggregateId(aggregateId);
        event.setPublished(false);
        event.setCreatedAt(Instant.now());
        // ADR-0032: captured here, in the same request/transaction as the
        // write — OutboxPoller runs later, in a scheduled thread with no
        // access to this MDC context, so the value has to be persisted
        // to survive that hop.
        event.setCorrelationId(MDC.get(CorrelationConstants.MDC_CORRELATION_ID_KEY));
        // ADR-0052: same reasoning as correlationId above.
        event.setTraceId(MDC.get(CorrelationConstants.MDC_TRACE_ID_KEY));
        // ADR-0062: order-service's own request handling is always the
        // saga's root — this is a synchronous Servlet request (not the
        // gateway's WebFlux reactive code ADR-0055 had to work around),
        // so Span.current() is reliably the real, live request span
        // here. Captured fresh on every publish call (not read back
        // from MDC, unlike correlationId/traceId above) since this
        // service is never itself a downstream saga consumer forwarding
        // someone else's span ID.
        SpanContext currentSpan = Span.current().getSpanContext();
        if (currentSpan.isValid()) {
            event.setSpanId(currentSpan.getSpanId());
        }
        try {
            event.setPayload(objectMapper.writeValueAsString(payload));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Event payload is not serializable: " + eventType.topic(), e);
        }
        outboxEventRepository.save(event);
    }
}
