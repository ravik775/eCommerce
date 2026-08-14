# ADR-0003: Eventing — dual-broker, Kafka for domain events, RabbitMQ for task queues

**Status**: Accepted
**Date**: 2026-08-12
**Deciders**: Solution/Security Architect

## Context

Notes.md/Architecture.txt name "Kafka / RabbitMQ" as the messaging layer without picking one. The order lifecycle needs two distinct communication shapes: (1) broadcasting a fact — "an order was created" — to multiple independent consumers (inventory, payment, notification), and (2) reliably processing a single unit of work exactly once with retry — "send this one email," "retry this one failed gateway call."

## Options Considered

| Option | Pros | Cons |
|---|---|---|
| Kafka only | One broker to operate; strong fit for the broadcast/replay use case | Modeling a work queue with per-message retry/DLQ on Kafka is possible but awkward — consumer offset management for retry semantics is more manual than a native queue |
| RabbitMQ only | Native work-queue/DLQ semantics; simple routing | Not designed for replay or high-throughput broadcast fan-out to many independent consumer groups; no log retention model for event replay |
| Kafka (domain events) + RabbitMQ (task queues), each used for what it's best at | Each broker used for its native strength; clear separation between "fact that happened" (Kafka) and "job to do" (RabbitMQ) | Two brokers to operate instead of one — accepted trade-off, matches the user's explicit instruction to use RabbitMQ "where it makes sense" rather than force one broker to do both jobs |

## Evidence

- Database/messaging-per-purpose reasoning follows the same logic as the well-established "use the right tool for the access pattern" principle documented for the analogous database-per-service pattern: "each service controls its... queries" and should not be forced into a one-size-fits-all store. ([microservices.io — Database per Service](https://microservices.io/patterns/data/database-per-service.html)) — applied here to messaging rather than storage, but the same reasoning (fitness-for-purpose over uniformity) applies.
- This decision was made directly per explicit user instruction ("Use RabbitMQ for communication where it makes sense") rather than derived from a benchmark — recorded honestly rather than backdated with invented research. The technical split (broadcast/replay → log-based broker; point-to-point/retry → queue-based broker) reflects each broker's documented design intent (Kafka: distributed commit log with consumer-group fan-out and replay; RabbitMQ: AMQP work queues with native DLQ) rather than a benchmarked comparison.

## Decision

- **Kafka** carries the domain-event backbone: `order-created`, `order-cancelled`, `order-returned`, `payment-success`, `payment-failed`, `inventory-reserved`, `inventory-released` — broadcast facts consumed by multiple independent services, benefiting from replay.
- **RabbitMQ** carries point-to-point task queues: `notification.dispatch` (with DLQ) for email/SMS delivery, `payment.gateway.retry` for downstream payment-gateway retry jobs — single-consumer work needing reliable retry/dead-letter handling.

## Consequences

- Positive: each broker does the job it's designed for; consumers of domain events don't need queue-semantics workarounds, and task queues don't need to fake replay/broadcast on top of Kafka.
- Negative / accepted trade-off: two message-broker infrastructure components to run, monitor, and secure instead of one — added to the stack only in the eventing phase (Phase 3) and containerization phase (Phase 5) of the delivery plan, not before.
- Follow-up required: define exact DLQ retry/backoff policy for `notification.dispatch` and `payment.gateway.retry` when Phase 3 is implemented.

## Related

- Related architecture doc: `doc/architecture/05-technology-architecture.md`
