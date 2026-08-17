package org.bgm.inventoryservice.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bgm.common.audit.AuditLogger;
import org.bgm.common.correlation.CorrelationConstants;
import org.bgm.common.correlation.OrderCorrelationScope;
import org.bgm.common.event.schema.EventType;
import org.bgm.inventoryservice.dto.BulkInventoryRequest;
import org.bgm.inventoryservice.dto.InventoryItemRequest;
import org.bgm.inventoryservice.exception.InsufficientStockException;
import org.bgm.inventoryservice.exception.ProductNotFoundException;
import org.bgm.inventoryservice.model.InventoryReservation;
import org.bgm.inventoryservice.model.ProcessedEvent;
import org.bgm.inventoryservice.repository.InventoryReservationRepository;
import org.bgm.inventoryservice.repository.ProcessedEventRepository;
import org.bgm.inventoryservice.service.InventoryEventPublisher;
import org.bgm.inventoryservice.service.InventoryService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ADR-0007: inventory-service's saga steps — reacts to `order-created` by
 * attempting a reservation, and to `payment-failed` by releasing a
 * previously-successful reservation (the compensating action).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventorySagaConsumer {

    private final InventoryService inventoryService;
    private final InventoryEventPublisher eventPublisher;
    private final InventoryReservationRepository reservationRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "order-created", groupId = "inventory-service")
    @Transactional
    public void onOrderCreated(
            String message,
            @Header(value = CorrelationConstants.MDC_CORRELATION_ID_KEY, required = false) String correlationId,
            @Header(value = CorrelationConstants.MDC_TRACE_ID_KEY, required = false) String traceId) throws Exception {
        OrderCreatedEvent event = objectMapper.readValue(message, OrderCreatedEvent.class);
        try (var ignored = OrderCorrelationScope.forOrder(event.orderId(), correlationId, traceId)) {
            if (processedEventRepository.existsById(event.eventId())) {
                return;
            }

            BulkInventoryRequest request = new BulkInventoryRequest(
                    event.orderId(),
                    event.items().stream()
                            .map(i -> new InventoryItemRequest(i.productId(), i.quantity()))
                            .toList()
            );

            try {
                inventoryService.reserve(request);
                recordReservations(event.orderId(), event.items());

                List<InventoryReservedEvent.Item> items = event.items().stream()
                        .map(i -> new InventoryReservedEvent.Item(i.productId(), i.quantity()))
                        .toList();
                InventoryReservedEvent reserved = new InventoryReservedEvent(
                        UUID.randomUUID().toString(), event.orderId(), items, Instant.now().toString());
                eventPublisher.publish(EventType.INVENTORY_RESERVED, event.orderId(), reserved.eventId(), reserved);
                // ADR-0059: this method previously had zero log output on
                // its success path — only the failure branch below ever
                // logged anything (found while auditing the same
                // silent-success class of gap ADR-0054 fixed for
                // notification-service). The only prior evidence a
                // reservation succeeded was the outbox_event DB row.
                AuditLogger.log("INVENTORY_RESERVED", AuditLogger.fields()
                        .with("orderId", event.orderId())
                        .with("itemCount", event.items().size())
                        .build());
            } catch (InsufficientStockException | ProductNotFoundException e) {
                InventoryReservationFailedEvent failed = new InventoryReservationFailedEvent(
                        UUID.randomUUID().toString(), event.orderId(), e.getMessage(), Instant.now().toString());
                eventPublisher.publish(EventType.INVENTORY_RESERVATION_FAILED, event.orderId(), failed.eventId(), failed);
                log.info("Reservation failed for order {}: {}", event.orderId(), e.getMessage());
            }

            markProcessed(event.eventId());
        }
    }

    @KafkaListener(topics = "payment-failed", groupId = "inventory-service")
    @Transactional
    public void onPaymentFailed(
            String message,
            @Header(value = CorrelationConstants.MDC_CORRELATION_ID_KEY, required = false) String correlationId,
            @Header(value = CorrelationConstants.MDC_TRACE_ID_KEY, required = false) String traceId) throws Exception {
        PaymentFailedEvent event = objectMapper.readValue(message, PaymentFailedEvent.class);
        try (var ignored = OrderCorrelationScope.forOrder(event.orderId(), correlationId, traceId)) {
            if (processedEventRepository.existsById(event.eventId())) {
                return;
            }

            List<InventoryReservation> reserved = reservationRepository.findByOrderIdAndStatus(
                    event.orderId(), InventoryReservation.ReservationStatus.RESERVED);
            if (!reserved.isEmpty()) {
                BulkInventoryRequest releaseRequest = new BulkInventoryRequest(
                        event.orderId(),
                        reserved.stream()
                                .map(r -> new InventoryItemRequest(r.getProductId(), r.getQuantity()))
                                .toList()
                );
                inventoryService.release(releaseRequest);
                reserved.forEach(r -> r.setStatus(InventoryReservation.ReservationStatus.RELEASED));
                reservationRepository.saveAll(reserved);

                List<InventoryReleasedEvent.Item> items = reserved.stream()
                        .map(r -> new InventoryReleasedEvent.Item(r.getProductId(), r.getQuantity()))
                        .toList();
                InventoryReleasedEvent released = new InventoryReleasedEvent(
                        UUID.randomUUID().toString(), event.orderId(), items, Instant.now().toString());
                eventPublisher.publish(EventType.INVENTORY_RELEASED, event.orderId(), released.eventId(), released);
                AuditLogger.log("INVENTORY_RELEASED", AuditLogger.fields()
                        .with("orderId", event.orderId())
                        .with("itemCount", items.size())
                        .build());
            }

            markProcessed(event.eventId());
        }
    }

    private void recordReservations(long orderId, List<OrderCreatedEvent.Item> items) {
        for (OrderCreatedEvent.Item item : items) {
            InventoryReservation reservation = new InventoryReservation();
            reservation.setOrderId(orderId);
            reservation.setProductId(item.productId());
            reservation.setQuantity(item.quantity());
            reservation.setStatus(InventoryReservation.ReservationStatus.RESERVED);
            reservationRepository.save(reservation);
        }
    }

    private void markProcessed(String eventId) {
        ProcessedEvent processed = new ProcessedEvent();
        processed.setEventId(eventId);
        processed.setProcessedAt(Instant.now());
        processedEventRepository.save(processed);
    }
}
