package org.bgm.orderservice.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

// ADR-0007 (doc/adr/ADR-0007-saga-outbox-idempotency.md): written in the
// SAME local transaction as the business write it accompanies (see
// OrderService.createOrder/updateStatus) — this is what makes event
// publishing atomic with the database write, closing the dual-write
// problem. A separate poller (OutboxPoller) drains unpublished rows to
// Kafka; nothing publishes directly from request-handling code.
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
}
