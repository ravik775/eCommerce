package org.bgm.paymentservice.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

// ADR-0007: same pattern as order-service's OutboxEvent.
@Getter
@Setter
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private String eventId;
    private String eventType;
    private long aggregateId;
    private String payload;
    private boolean published;
    private Instant createdAt;
    private Instant publishedAt;

    // ADR-0032: see order-service's OutboxEvent for the full rationale.
    private String correlationId;

    // ADR-0052: see order-service's OutboxEvent for the full rationale.
    private String traceId;
}
