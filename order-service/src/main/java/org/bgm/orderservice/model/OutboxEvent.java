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

    // ADR-0032: the correlation ID as of the moment this row was written
    // (captured from MDC in the same request/transaction) — see
    // OutboxPoller for how this survives the hop to Kafka as a message
    // header, and OrderEventPublisher for where it's captured.
    private String correlationId;

    // ADR-0052: same mechanism as correlationId above, for the
    // gateway-generated X-Trace-Id — see OrderCorrelationScope's
    // Javadoc for why this flows across the async saga even though the
    // real OTel span tree doesn't.
    private String traceId;

    // ADR-0062: the real OTel span ID of the request that created this
    // row — order-service is always the saga's root, so this is always
    // a fresh live capture here (never forwarded from elsewhere), and
    // every downstream service just carries it forward unchanged. See
    // CorrelationConstants.MDC_SPAN_ID_KEY's Javadoc for why.
    private String spanId;
}
