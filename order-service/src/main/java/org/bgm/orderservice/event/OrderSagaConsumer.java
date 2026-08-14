package org.bgm.orderservice.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bgm.orderservice.model.ProcessedEvent;
import org.bgm.orderservice.repository.ProcessedEventRepository;
import org.bgm.orderservice.service.OrderService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * ADR-0007 (doc/adr/ADR-0007-saga-outbox-idempotency.md): the choreography
 * saga's order-side reactions. Each listener is idempotent — checks
 * ProcessedEvent before applying an event's effect, since Kafka is
 * at-least-once delivery and the same event may arrive more than once.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderSagaConsumer {

    private final OrderService orderService;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "payment-success", groupId = "order-service")
    @Transactional
    public void onPaymentSuccess(String message) throws Exception {
        PaymentSuccessEvent event = objectMapper.readValue(message, PaymentSuccessEvent.class);
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        orderService.applyPaymentOutcome(event.orderId(), true);
        markProcessed(event.eventId());
    }

    @KafkaListener(topics = "payment-failed", groupId = "order-service")
    @Transactional
    public void onPaymentFailed(String message) throws Exception {
        PaymentFailedEvent event = objectMapper.readValue(message, PaymentFailedEvent.class);
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        orderService.applyPaymentOutcome(event.orderId(), false);
        markProcessed(event.eventId());
        log.info("Order {} payment failed: {}", event.orderId(), event.reason());
    }

    @KafkaListener(topics = "inventory-reservation-failed", groupId = "order-service")
    @Transactional
    public void onInventoryReservationFailed(String message) throws Exception {
        InventoryReservationFailedEvent event = objectMapper.readValue(message, InventoryReservationFailedEvent.class);
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        orderService.applyInventoryReservationFailure(event.orderId());
        markProcessed(event.eventId());
        log.info("Order {} inventory reservation failed: {}", event.orderId(), event.reason());
    }

    private boolean alreadyProcessed(String eventId) {
        return processedEventRepository.existsById(eventId);
    }

    private void markProcessed(String eventId) {
        ProcessedEvent processed = new ProcessedEvent();
        processed.setEventId(eventId);
        processed.setProcessedAt(Instant.now());
        processedEventRepository.save(processed);
    }
}
