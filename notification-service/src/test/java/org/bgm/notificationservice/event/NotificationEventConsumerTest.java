package org.bgm.notificationservice.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bgm.common.correlation.CorrelationConstants;
import org.bgm.notificationservice.dispatch.NotificationDispatchMessage;
import org.bgm.notificationservice.repository.ProcessedEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression case for the RabbitMQ-hop MDC gap: found live, a real
 * checkout's notification.dispatch worker logged blank correlationId/
 * orderId/appTraceId despite the Kafka-consumer thread (this class) having
 * them correctly in MDC — because MDC is thread-local and
 * NotificationDispatchWorker runs on a separate RabbitMQ listener thread.
 * Locks down the fix's producer side: dispatch() must carry the current
 * MDC values as RabbitMQ message headers, the same way OutboxPoller
 * already does for the Kafka hop (ADR-0052).
 */
class NotificationEventConsumerTest {

    private CapturingRabbitTemplate rabbitTemplate;
    private ProcessedEventRepository processedEventRepository;
    private NotificationEventConsumer consumer;

    @BeforeEach
    void setUp() {
        // RabbitTemplate can't be Mockito-mocked on this JDK (inline
        // mock-maker fails to instrument it) — a small subclass that
        // just records the call is simpler than chasing that anyway,
        // and avoids depending on Mockito's bytecode-generation
        // internals for a single method call.
        rabbitTemplate = new CapturingRabbitTemplate();
        processedEventRepository = mock(ProcessedEventRepository.class);
        when(processedEventRepository.existsById(anyString())).thenReturn(false);
        consumer = new NotificationEventConsumer(rabbitTemplate, processedEventRepository, new ObjectMapper());
        ReflectionTestUtils.setField(consumer, "dispatchExchange", "notification.exchange");
        ReflectionTestUtils.setField(consumer, "dispatchRoutingKey", "notification.dispatch");
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void onPaymentSuccessCarriesCorrelationContextAsRabbitMessageHeaders() throws Exception {
        String json = new ObjectMapper().writeValueAsString(new PaymentSuccessEvent("evt-1", 66L));

        consumer.onPaymentSuccess(json, "corr-123", "trace-abc", null);

        MessagePostProcessor postProcessor = capturePostProcessor();
        Message message = new Message(new byte[0], new MessageProperties());
        postProcessor.postProcessMessage(message);

        assertEquals("corr-123", message.getMessageProperties().getHeader(CorrelationConstants.MDC_CORRELATION_ID_KEY));
        assertEquals("66", message.getMessageProperties().getHeader(CorrelationConstants.MDC_ORDER_ID_KEY));
        assertEquals("trace-abc", message.getMessageProperties().getHeader(CorrelationConstants.MDC_TRACE_ID_KEY));
    }

    @Test
    void onPaymentFailedCarriesCorrelationContextAsRabbitMessageHeaders() throws Exception {
        String json = new ObjectMapper().writeValueAsString(new PaymentFailedEvent("evt-2", 67L, "declined"));

        consumer.onPaymentFailed(json, "corr-456", "trace-def", null);

        MessagePostProcessor postProcessor = capturePostProcessor();
        Message message = new Message(new byte[0], new MessageProperties());
        postProcessor.postProcessMessage(message);

        assertEquals("corr-456", message.getMessageProperties().getHeader(CorrelationConstants.MDC_CORRELATION_ID_KEY));
        assertEquals("67", message.getMessageProperties().getHeader(CorrelationConstants.MDC_ORDER_ID_KEY));
        assertEquals("trace-def", message.getMessageProperties().getHeader(CorrelationConstants.MDC_TRACE_ID_KEY));
    }

    @Test
    void omitsHeadersWhenNoTraceIdWasPropagated() throws Exception {
        String json = new ObjectMapper().writeValueAsString(new PaymentSuccessEvent("evt-3", 68L));

        // No traceId header on the inbound Kafka message — degrades the
        // same way OrderCorrelationScope itself degrades (traceId simply
        // left unset), not synthesized as a placeholder.
        consumer.onPaymentSuccess(json, "corr-789", null, null);

        MessagePostProcessor postProcessor = capturePostProcessor();
        Message message = new Message(new byte[0], new MessageProperties());
        postProcessor.postProcessMessage(message);

        assertEquals("corr-789", message.getMessageProperties().getHeader(CorrelationConstants.MDC_CORRELATION_ID_KEY));
        assertNull(message.getMessageProperties().getHeader(CorrelationConstants.MDC_TRACE_ID_KEY));
    }

    private MessagePostProcessor capturePostProcessor() {
        assertEquals(1, rabbitTemplate.capturedPostProcessors.size(), "expected exactly one convertAndSend call");
        return rabbitTemplate.capturedPostProcessors.get(0);
    }

    /** Test double: records the MessagePostProcessor from each convertAndSend call, does not touch a real broker. */
    private static class CapturingRabbitTemplate extends RabbitTemplate {
        private final java.util.List<MessagePostProcessor> capturedPostProcessors = new java.util.ArrayList<>();

        @Override
        public void convertAndSend(String exchange, String routingKey, Object message, MessagePostProcessor messagePostProcessor) {
            capturedPostProcessors.add(messagePostProcessor);
        }
    }
}
