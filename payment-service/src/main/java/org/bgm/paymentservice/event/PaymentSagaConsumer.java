package org.bgm.paymentservice.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bgm.common.audit.AuditLogger;
import org.bgm.common.correlation.CorrelationConstants;
import org.bgm.common.correlation.OrderCorrelationScope;
import org.bgm.common.event.schema.EventType;
import org.bgm.paymentservice.client.OrderServiceClient;
import org.bgm.paymentservice.dto.InitiatePaymentRequest;
import org.bgm.paymentservice.model.Payment;
import org.bgm.paymentservice.model.PaymentMethod;
import org.bgm.paymentservice.model.PaymentStatus;
import org.bgm.paymentservice.model.ProcessedEvent;
import org.bgm.paymentservice.repository.ProcessedEventRepository;
import org.bgm.paymentservice.service.PaymentEventPublisher;
import org.bgm.paymentservice.service.PaymentService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * ADR-0007: payment-service's saga step — reacts to `inventory-reserved`
 * by charging the order (amount looked up via OrderServiceClient, since
 * that event doesn't carry it) and publishing the terminal outcome.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentSagaConsumer {

    private final PaymentService paymentService;
    private final OrderServiceClient orderServiceClient;
    private final PaymentEventPublisher eventPublisher;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "inventory-reserved", groupId = "payment-service")
    @Transactional
    public void onInventoryReserved(
            String message,
            @Header(value = CorrelationConstants.MDC_CORRELATION_ID_KEY, required = false) String correlationId,
            @Header(value = CorrelationConstants.MDC_TRACE_ID_KEY, required = false) String traceId,
            @Header(value = CorrelationConstants.MDC_SPAN_ID_KEY, required = false) String parentSpanId) throws Exception {
        InventoryReservedEvent event = objectMapper.readValue(message, InventoryReservedEvent.class);
        try (var ignored = OrderCorrelationScope.forOrder(event.orderId(), correlationId, traceId, parentSpanId)) {
            if (processedEventRepository.existsById(event.eventId())) {
                return;
            }

            double amount = orderServiceClient.getOrderAmount(event.orderId());
            // Method defaults to CREDIT_CARD: the saga has no checkout-time
            // method selection input yet (that arrives with the UI, Phase 8) —
            // documented simplification, not a silent gap.
            InitiatePaymentRequest request = new InitiatePaymentRequest(event.orderId(), amount, PaymentMethod.CREDIT_CARD);
            Payment payment = paymentService.initiate(request);

            String eventId = UUID.randomUUID().toString();
            // Phase 7 audit trail: the payment leg, closing out the login →
            // order → payment sequence — joined to the order leg by orderId
            // (see AuditLogger's Javadoc for why orderId/customerId, not the
            // HTTP correlation ID, are the actual join keys across this
            // Kafka-based saga).
            if (payment.getStatus() == PaymentStatus.SUCCESS) {
                PaymentSuccessEvent success = new PaymentSuccessEvent(
                        eventId, event.orderId(), payment.getTransactionId(), payment.getAmount(), Instant.now().toString());
                eventPublisher.publish(EventType.PAYMENT_SUCCESS, event.orderId(), eventId, success);
                AuditLogger.log("PAYMENT_SUCCESS", AuditLogger.fields()
                        .with("orderId", event.orderId())
                        .with("transactionId", payment.getTransactionId())
                        .with("amount", payment.getAmount())
                        .build());
            } else {
                PaymentFailedEvent failed = new PaymentFailedEvent(
                        eventId, event.orderId(), "Payment processor declined", Instant.now().toString());
                eventPublisher.publish(EventType.PAYMENT_FAILED, event.orderId(), eventId, failed);
                AuditLogger.log("PAYMENT_FAILED", AuditLogger.fields()
                        .with("orderId", event.orderId())
                        .with("reason", "Payment processor declined")
                        .build());
            }

            markProcessed(event.eventId());
        }
    }

    private void markProcessed(String eventId) {
        ProcessedEvent processed = new ProcessedEvent();
        processed.setEventId(eventId);
        processed.setProcessedAt(Instant.now());
        processedEventRepository.save(processed);
    }
}
