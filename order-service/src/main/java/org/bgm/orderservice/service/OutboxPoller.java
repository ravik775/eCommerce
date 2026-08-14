package org.bgm.orderservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bgm.orderservice.model.OutboxEvent;
import org.bgm.orderservice.repository.OutboxEventRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * ADR-0007: separate process that reads the outbox table and forwards
 * unpublished rows to Kafka, marking them published only after a broker
 * ack. This — not request-handling code — is the only thing that ever
 * publishes to Kafka.
 */
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
                kafkaTemplate.send(event.getEventType(), String.valueOf(event.getAggregateId()), event.getPayload())
                        .get(); // synchronous ack within this poll tick — simplest correct behavior at this volume
                event.setPublished(true);
                event.setPublishedAt(Instant.now());
                outboxEventRepository.save(event);
            } catch (Exception e) {
                // Left unpublished — picked up again next tick. Not marking
                // published on failure is what keeps this at-least-once.
                log.warn("Failed to publish outbox event id={} type={}, will retry", event.getId(), event.getEventType(), e);
            }
        }
    }
}
