package org.bgm.inventoryservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.bgm.common.correlation.CorrelationConstants;
import org.bgm.inventoryservice.model.OutboxEvent;
import org.bgm.inventoryservice.repository.OutboxEventRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

// ADR-0007: same pattern as order-service's OutboxPoller.
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPoller {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void drain() {
        List<OutboxEvent> unpublished = outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc();
        for (OutboxEvent event : unpublished) {
            try {
                ProducerRecord<String, String> record = new ProducerRecord<>(
                        event.getEventType(), String.valueOf(event.getAggregateId()), event.getPayload());
                if (event.getCorrelationId() != null) {
                    record.headers().add(new RecordHeader(
                            CorrelationConstants.MDC_CORRELATION_ID_KEY,
                            event.getCorrelationId().getBytes(StandardCharsets.UTF_8)));
                }
                // ADR-0052: same mechanism as correlationId above.
                if (event.getTraceId() != null) {
                    record.headers().add(new RecordHeader(
                            CorrelationConstants.MDC_TRACE_ID_KEY,
                            event.getTraceId().getBytes(StandardCharsets.UTF_8)));
                }
                kafkaTemplate.send(record).get();
                event.setPublished(true);
                event.setPublishedAt(Instant.now());
                outboxEventRepository.save(event);
            } catch (Exception e) {
                log.warn("Failed to publish outbox event id={} type={}, will retry", event.getId(), event.getEventType(), e);
            }
        }
    }
}
