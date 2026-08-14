package org.bgm.inventoryservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bgm.inventoryservice.model.OutboxEvent;
import org.bgm.inventoryservice.repository.OutboxEventRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
                kafkaTemplate.send(event.getEventType(), String.valueOf(event.getAggregateId()), event.getPayload()).get();
                event.setPublished(true);
                event.setPublishedAt(Instant.now());
                outboxEventRepository.save(event);
            } catch (Exception e) {
                log.warn("Failed to publish outbox event id={} type={}, will retry", event.getId(), event.getEventType(), e);
            }
        }
    }
}
