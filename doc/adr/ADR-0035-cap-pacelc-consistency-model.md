# ADR-0035: CAP/PACELC positioning and consistency model

**Status**: Accepted
**Date**: 2026-08-16
**Deciders**: Solution/Security Architect

## Context

The system already makes a real CAP/PACELC trade-off — the saga/outbox pattern (ADR-0007) is, in CAP terms, an explicit choice of eventual consistency over immediate cross-service consistency — but that choice was never named or justified in those terms anywhere in the documentation. A reviewer evaluating this architecture against distributed-systems fundamentals has no written answer to "where does this system sit on CAP, and why." This ADR names the trade-off explicitly rather than leaving it implicit in ADR-0007's mechanics.

CAP applies per data store, not to a system as a whole — each store here needs its own answer.

## Options Considered

Not a build/buy choice like most ADRs in this repo — this is a documentation-of-existing-architecture exercise. The "options" are different consistency models the saga could have used:

| Option | Consistency | Availability under partition | Already how this system works? |
|---|---|---|---|
| Distributed transaction (2PC) across order/inventory/payment | Strong (C) | Poor — any participant down blocks the whole transaction | No |
| Saga + outbox, eventual consistency (current) | Eventual (A-leaning) | Good — each service progresses independently, catches up when the others are reachable | **Yes** |
| Synchronous REST call chain, no saga | Strong-ish (but no atomicity guarantee either) | Poor — a single downstream outage fails the whole chain (this is exactly the failure mode the circuit breaker on order→payment guards against) | No (this is what was explicitly avoided) |

## Evidence

- General distributed-systems practice (Brewer's CAP theorem; Abadi's PACELC extension), not a project-specific benchmark: CAP only forces a choice *during a network partition*. PACELC is the more honest framing for a system like this one, because it also asks what you choose when there's **no** partition — this system chooses low **L**atency over strong Consistency there too (each saga step commits locally and moves on; it doesn't wait for a synchronous ack from every downstream service before considering an order "created").
- Per-store reality, confirmed against the actual manifests (see ADR-0034 for the source data): Postgres, Kafka, and Redis in this deployment are all **single-node** (`replicas: 1` everywhere). A single node cannot itself experience an internal network partition — so CAP's "P" (partition tolerance) is not really being tested by any of this system's own infrastructure today. The relevant partition scenario is between *services* (e.g. order-service can't reach payment-service, or can't reach Kafka), not within a single store.

## Decision

**PACELC classification: PA/EL** — when there's a partition between services (order-service can't reach inventory-service, or Kafka is unreachable), the system chooses **Availability** (the calling service still responds, via the circuit breaker's fallback or by writing to its own outbox and letting the poller retry later) over strict cross-service Consistency. When there's no partition (the normal case), it chooses **Low Latency** over waiting for synchronous cross-service confirmation.

Concretely, per interaction:
- **Within a single service's own Postgres schema** (e.g. order-service's own `orders` + `outbox_event` tables in one transaction): strongly consistent — ACID, same as any single-node RDBMS.
- **Across services** (order → inventory reservation → payment → notification): eventually consistent, mediated by the outbox pattern + Kafka + each consumer's own idempotent processing (ADR-0024). A customer can observe an order in `PENDING` state for a real (bounded, but non-zero) window before payment/inventory catch up.
- **Under a downstream outage**: the calling service stays available (order creation still succeeds; the circuit breaker fails fast on order→payment rather than hanging) at the cost of temporarily stale/pending state elsewhere — Availability over Consistency, by design, not by accident.

## Consequences

- Positive: this is now a named, defended design choice instead of an implicit side effect of "the saga pattern happened to work this way." A future reviewer (or this exercise's SOC 2 mapping, ADR-0036) can point at this document instead of re-deriving the reasoning from `OrderService.java`.
- Negative / accepted trade-off: eventual consistency means the UI can show a customer an order that later fails downstream (e.g. inventory reservation fails after payment succeeded) — this requires compensating logic (already partially present via the saga's failure-path events) and means "read your own write" isn't always true immediately after checkout. This is accepted as inherent to the chosen pattern, not a bug.
- Follow-up required: none — this ADR documents an existing, already-implemented trade-off. Revisit only if a future requirement demands strong cross-service consistency (e.g. a regulatory requirement that inventory and payment must never observably diverge, even briefly) — at which point the honest answer is a different architecture, not a config change.

## Related

- Related: ADR-0007 (saga/outbox pattern — the mechanism this ADR names in CAP/PACELC terms), ADR-0024 (idempotency — what makes eventual consistency safe to retry), ADR-0034 (backup/replication posture — the per-store replica-count data this ADR's Evidence section relies on)
