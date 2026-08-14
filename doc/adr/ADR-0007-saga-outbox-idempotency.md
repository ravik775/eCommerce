# ADR-0007: Distributed transaction pattern — Choreography Saga + Transactional Outbox + idempotent consumers

**Status**: Accepted
**Date**: 2026-08-12
**Deciders**: Solution/Security Architect (raised in architect self-review, `doc/architecture/11-architect-review.md`, Gap #1)

## Context

The order→inventory→payment flow spans three services and two Kafka event hops with no single database transaction covering all of it. Without an explicit pattern, two real correctness problems exist: (1) what happens when a step fails after a prior step already succeeded (e.g., payment fails after inventory was reserved), and (2) the dual-write problem — a service writing to its own database and publishing an event are two separate operations that can fail independently, leaving the database and the event stream disagreeing about what happened.

## Options Considered

| Option | Pros | Cons |
|---|---|---|
| Choreography Saga + Transactional Outbox + idempotent consumers | No central coordinator (matches the existing choreography-shaped design); outbox makes the DB write and the event publish atomic, closing the dual-write gap; idempotent consumers make Kafka's at-least-once delivery safe | Compensating logic is spread across services rather than centralized in one place — harder to see "the whole saga" in one file |
| Orchestration Saga (dedicated coordinator service) | Centralized visibility and easier testing of the end-to-end flow; one place to change the saga's shape | New coordinating component and a new central dependency every step relies on; a bigger structural change from the current design; more infrastructure for a 3-participant flow |
| Defer — no outbox, no explicit saga, accept at-least-once risk | Fastest to build, no new code pattern to introduce | Real correctness gap ships to production: a crash between DB commit and event publish silently loses or duplicates business events; explicitly rejected by user decision |

## Evidence

- Saga pattern is the standard fix for exactly this class of problem: "a sequence of local transactions... if a local transaction fails, the saga executes a series of compensating transactions to undo the changes made by preceding successful transactions." ([microservices.io — Saga](https://microservices.io/patterns/data/saga.html))
- Choreography specifically fits this project's scale: "suitable when there are only a few participants in the saga, and you need a simple implementation with no single point of failure" — this flow has three participants (order, inventory, payment), matching the case choreography is documented to fit best. ([Conduktor — Saga Pattern](https://www.conduktor.io/glossary/saga-pattern-for-distributed-transactions))
- The dual-write problem and its standard fix: "The outbox pattern transforms the dual-write problem into a single-write problem by treating event publishing as part of the database transaction... writing each event to an outbox table inside the same database transaction as the business data it describes... a separate process then reads that table and forwards the events to a message broker... because the state change and the event commit together, the two can never disagree." ([Conduktor — Outbox Pattern](https://www.conduktor.io/glossary/outbox-pattern-for-reliable-event-publishing))
- Outbox tail implementation choice: polling (simpler, adds latency) vs. change data capture/CDC (cleaner, lower latency, more moving parts). Given this project's scale, polling is the pragmatic starting point; CDC (e.g., Debezium) is a documented upgrade path if latency ever demands it, not a Day 1 requirement.

## Decision

Use **choreography-based Sagas** for the order→inventory→payment flow — no central orchestrator, each service reacts to the previous service's Kafka event, consistent with the existing design.

Every event-publishing service (order-service, inventory-service, payment-service) implements the **Transactional Outbox pattern**: the business-data write and the outbox-row write happen in the same local database transaction (same Postgres schema, per ADR-0004); a separate poller reads the outbox table and publishes to Kafka, marking rows published only after a broker ack.

Every Kafka consumer is **idempotent**: consumers track processed event IDs (or use natural idempotency keys, e.g., `order_id` + event type) and safely no-op on redelivery, since Kafka is at-least-once by default.

**Compensating actions** for the order flow: `payment-failed` triggers an `inventory-release` compensating action from inventory-service; `order-cancelled`/`order-returned` already trigger inventory release/restock per the existing business-architecture flow — these are now explicitly framed as saga compensations, not ad hoc event handlers.

## Consequences

- Positive: closes a real correctness gap (dual-write, non-idempotent consumption) that the original design left implicit; no new infrastructure component required (outbox is a table in each service's existing schema, ADR-0004); stays consistent with the existing choreography shape rather than requiring a redesign.
- Negative / accepted trade-off: each event-publishing service needs an outbox table + poller (a repeated but small pattern, not a shared infra component); saga logic (what compensates what) is spread across services rather than visible in one orchestrator — acceptable at this project's 3-participant scale, revisit toward orchestration if the saga grows significantly more complex.
- Follow-up required: define the outbox table schema and poller mechanism during Phase 3 (Eventing) implementation; document the specific compensating-action map per event in `doc/architecture/02-business-architecture.md`.

## Related

- Supersedes: no formal prior ADR (the gap was undocumented, not previously decided differently) — resolves Gap #1 from `doc/architecture/11-architect-review.md`.
- Related architecture docs: `doc/architecture/02-business-architecture.md`, `doc/architecture/03-data-architecture.md`, ADR-0003 (eventing), ADR-0004 (data ownership)
