package org.bgm.notificationservice.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.bgm.common.audit.AuditLogger;
import org.bgm.common.correlation.CorrelationConstants;
import org.bgm.common.correlation.OrderCorrelationScope;
import org.bgm.notificationservice.dispatch.NotificationDispatchMessage;
import org.bgm.notificationservice.model.ProcessedEvent;
import org.bgm.notificationservice.repository.ProcessedEventRepository;
import org.slf4j.MDC;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * ADR-0003: the Kafka -> RabbitMQ bridge. Consumes terminal saga events
 * (payment-success/payment-failed) and publishes a work item onto the
 * notification.dispatch queue — decoupling "an event happened" (Kafka,
 * broadcast, this class) from "send this one notification reliably,
 * retry on failure" (RabbitMQ, work queue, NotificationDispatchWorker).
 */
@Component
@RequiredArgsConstructor
public class NotificationEventConsumer {

    private final RabbitTemplate rabbitTemplate;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    @Value("${notification.rabbitmq.dispatch-exchange:notification.exchange}")
    private String dispatchExchange;

    @Value("${notification.rabbitmq.dispatch-queue:notification.dispatch}")
    private String dispatchRoutingKey;

    @KafkaListener(topics = "payment-success", groupId = "notification-service")
    @Transactional
    public void onPaymentSuccess(
            String message,
            @Header(value = CorrelationConstants.MDC_CORRELATION_ID_KEY, required = false) String correlationId,
            @Header(value = CorrelationConstants.MDC_TRACE_ID_KEY, required = false) String traceId) throws Exception {
        PaymentSuccessEvent event = objectMapper.readValue(message, PaymentSuccessEvent.class);
        // Last hop of the order saga (order-service -> inventory-service ->
        // payment-service already do this) — was missing here, so a
        // saga's log trail went cold right before the notification step,
        // the one place an operator would most want to confirm delivery
        // actually happened for a given order.
        try (var ignored = OrderCorrelationScope.forOrder(event.orderId(), correlationId, traceId)) {
            if (alreadyProcessed(event.eventId())) {
                return;
            }
            dispatch(event.orderId(), NotificationDispatchMessage.TYPE_ORDER_CONFIRMATION);
            AuditLogger.log("NOTIFICATION_DISPATCH_QUEUED", AuditLogger.fields()
                    .with("orderId", event.orderId())
                    .with("type", NotificationDispatchMessage.TYPE_ORDER_CONFIRMATION)
                    .build());
            markProcessed(event.eventId());
        }
    }

    @KafkaListener(topics = "payment-failed", groupId = "notification-service")
    @Transactional
    public void onPaymentFailed(
            String message,
            @Header(value = CorrelationConstants.MDC_CORRELATION_ID_KEY, required = false) String correlationId,
            @Header(value = CorrelationConstants.MDC_TRACE_ID_KEY, required = false) String traceId) throws Exception {
        PaymentFailedEvent event = objectMapper.readValue(message, PaymentFailedEvent.class);
        try (var ignored = OrderCorrelationScope.forOrder(event.orderId(), correlationId, traceId)) {
            if (alreadyProcessed(event.eventId())) {
                return;
            }
            dispatch(event.orderId(), NotificationDispatchMessage.TYPE_PAYMENT_FAILED);
            AuditLogger.log("NOTIFICATION_DISPATCH_QUEUED", AuditLogger.fields()
                    .with("orderId", event.orderId())
                    .with("type", NotificationDispatchMessage.TYPE_PAYMENT_FAILED)
                    .build());
            markProcessed(event.eventId());
        }
    }

    // Carries correlationId/orderId/appTraceId across the Kafka-consumer-
    // thread -> RabbitMQ-listener-thread hop as message headers — the same
    // problem OutboxPoller already solved for the Kafka hop (ADR-0052),
    // applied here because MDC is thread-local and NotificationDispatchWorker
    // runs on a different thread than this method. Read from MDC (not
    // passed as a parameter) since this runs inside OrderCorrelationScope,
    // which is the single source of truth for "what's the current
    // correlation context" — matches how OutboxPoller itself reads MDC
    // rather than threading the values through every call site.
    private void dispatch(long orderId, String type) {
        NotificationDispatchMessage dispatchMessage =
                new NotificationDispatchMessage(orderId, type, Instant.now().toString());
        String correlationId = MDC.get(CorrelationConstants.MDC_CORRELATION_ID_KEY);
        String orderIdHeader = MDC.get(CorrelationConstants.MDC_ORDER_ID_KEY);
        String appTraceId = MDC.get(CorrelationConstants.MDC_TRACE_ID_KEY);
        rabbitTemplate.convertAndSend(dispatchExchange, dispatchRoutingKey, dispatchMessage, message -> {
            MessageProperties props = message.getMessageProperties();
            if (correlationId != null && !correlationId.isBlank()) {
                props.setHeader(CorrelationConstants.MDC_CORRELATION_ID_KEY, correlationId);
            }
            if (orderIdHeader != null && !orderIdHeader.isBlank()) {
                props.setHeader(CorrelationConstants.MDC_ORDER_ID_KEY, orderIdHeader);
            }
            if (appTraceId != null && !appTraceId.isBlank()) {
                props.setHeader(CorrelationConstants.MDC_TRACE_ID_KEY, appTraceId);
            }
            return message;
        });
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
