package org.bgm.paymentservice.service;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.bgm.common.correlation.CorrelationConstants;
import org.bgm.paymentservice.model.OutboxEvent;
import org.bgm.paymentservice.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 0 (outbox-deduplication refactor plan): characterization tests
 * for CURRENT behavior, written before any common-lib extraction —
 * mirrors inventory-service's OutboxPollerTest (identical class shape),
 * the baseline every later phase must stay green against.
 */
class OutboxPollerTest {

    private OutboxEventRepository repository;
    private KafkaTemplate<String, String> kafkaTemplate;
    private OutboxPoller poller;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        poller = new OutboxPoller(repository, kafkaTemplate);
    }

    private OutboxEvent unpublishedEvent(String correlationId, String traceId, String spanId) {
        OutboxEvent event = new OutboxEvent();
        event.setId(1L);
        event.setEventId("evt-1");
        event.setEventType("payment-success");
        event.setAggregateId(42L);
        event.setPayload("{}");
        event.setPublished(false);
        event.setCorrelationId(correlationId);
        event.setTraceId(traceId);
        event.setSpanId(spanId);
        return event;
    }

    @SuppressWarnings("unchecked")
    private void stubSuccessfulSend() {
        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(mock(SendResult.class));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);
    }

    @Test
    void publishesUnpublishedEventAndMarksItPublished() {
        OutboxEvent event = unpublishedEvent("corr-1", "trace-1", "span-1");
        when(repository.findByPublishedFalseOrderByCreatedAtAsc()).thenReturn(List.of(event));
        stubSuccessfulSend();

        poller.drain();

        assertTrue(event.isPublished());
        assertTrue(event.getPublishedAt() != null);
        verify(repository).save(event);
    }

    @Test
    void attachesCorrelationTraceAndSpanHeadersWhenPresent() {
        OutboxEvent event = unpublishedEvent("corr-1", "trace-1", "span-1");
        when(repository.findByPublishedFalseOrderByCreatedAtAsc()).thenReturn(List.of(event));
        stubSuccessfulSend();

        poller.drain();

        var captor = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, String> sent = captor.getValue();
        assertHeaderEquals(sent, CorrelationConstants.MDC_CORRELATION_ID_KEY, "corr-1");
        assertHeaderEquals(sent, CorrelationConstants.MDC_TRACE_ID_KEY, "trace-1");
        assertHeaderEquals(sent, CorrelationConstants.MDC_SPAN_ID_KEY, "span-1");
    }

    @Test
    void omitsHeadersWhenFieldsAreNull() {
        OutboxEvent event = unpublishedEvent(null, null, null);
        when(repository.findByPublishedFalseOrderByCreatedAtAsc()).thenReturn(List.of(event));
        stubSuccessfulSend();

        poller.drain();

        var captor = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, String> sent = captor.getValue();
        assertNull(sent.headers().lastHeader(CorrelationConstants.MDC_CORRELATION_ID_KEY));
        assertNull(sent.headers().lastHeader(CorrelationConstants.MDC_TRACE_ID_KEY));
        assertNull(sent.headers().lastHeader(CorrelationConstants.MDC_SPAN_ID_KEY));
    }

    @Test
    void leavesEventUnpublishedAndDoesNotThrowWhenSendFails() {
        OutboxEvent event = unpublishedEvent("corr-1", "trace-1", "span-1");
        when(repository.findByPublishedFalseOrderByCreatedAtAsc()).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenThrow(new RuntimeException("broker unreachable"));

        poller.drain();

        assertFalse(event.isPublished());
        verify(repository, never()).save(any());
    }

    @Test
    void noUnpublishedEventsIsANoOp() {
        when(repository.findByPublishedFalseOrderByCreatedAtAsc()).thenReturn(List.of());

        poller.drain();

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(repository, never()).save(any());
    }

    private void assertHeaderEquals(ProducerRecord<String, String> record, String key, String expected) {
        var header = record.headers().lastHeader(key);
        assertTrue(header != null, "expected header " + key + " to be present");
        assertTrue(new String(header.value()).equals(expected));
    }
}
