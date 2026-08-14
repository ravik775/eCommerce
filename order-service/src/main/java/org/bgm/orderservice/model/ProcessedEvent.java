package org.bgm.orderservice.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

// ADR-0007: Kafka is at-least-once delivery — consumers must be idempotent
// against redelivery. One row per successfully-processed eventId; a
// listener checks this table before applying an event's effect.
@Getter
@Setter
@Entity
@Table(name = "processed_event")
public class ProcessedEvent {
    @Id
    private String eventId;
    private Instant processedAt;
}
