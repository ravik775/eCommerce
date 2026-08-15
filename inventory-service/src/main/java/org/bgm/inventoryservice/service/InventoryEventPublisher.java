package org.bgm.inventoryservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.bgm.common.correlation.CorrelationConstants;
import org.bgm.common.event.schema.EventSchemaValidator;
import org.bgm.common.event.schema.EventType;
import org.bgm.inventoryservice.model.OutboxEvent;
import org.bgm.inventoryservice.repository.OutboxEventRepository;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

// ADR-0007: same pattern as order-service's OrderEventPublisher.
@Service
@RequiredArgsConstructor
public class InventoryEventPublisher {

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
        event.setCorrelationId(MDC.get(CorrelationConstants.MDC_CORRELATION_ID_KEY));
        try {
            event.setPayload(objectMapper.writeValueAsString(payload));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Event payload is not serializable: " + eventType.topic(), e);
        }
        outboxEventRepository.save(event);
    }
}
