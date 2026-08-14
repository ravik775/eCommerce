# 11 — Architect Review: Completeness, Simplicity, and Event-Driven Rigor

**Reviewer**: Solution/Security Architect (self-review of `doc/architecture/00`–`10` and `doc/adr/0001`–`0006`)
**Date**: 2026-08-12
**Verdict**: Documentation is structurally sound and every *stated* decision has evidence — but the review surfaced **one real gap** (a missing decision, not just a missing document) and **two real conflicts** between the "not complicated" principle and components inherited without challenge. Recorded honestly below rather than silently patched.

## What Passed Review

- Every ADR (0001–0006) has Context, Options Considered (genuine alternatives, not strawmen), Evidence with working links, Decision, and Consequences — matches `doc/adr/template.md` consistently.
- Cross-references between architecture docs and ADRs are correct and bidirectional.
- ADR-0002 is a strong example of the "evidence over instinct" principle actually being followed: it documents a decision that was *reversed twice* (Istio → Linkerd+SPIRE → plain SPIRE) with the evidence at each step, rather than presenting only the final answer.
- The dual-broker eventing decision (ADR-0003) is honestly labeled as originating from explicit user instruction, not fabricated research — this is the right way to record a directive-driven decision rather than dressing it up as independently discovered.

## Gap #1 (real, not cosmetic): No decision on distributed data consistency

**Finding**: `02-business-architecture.md` narrates the order→inventory→payment event flow, but no ADR commits to *how* correctness is maintained across that flow. Three concrete real-world problems are currently undocumented:

1. **Saga coordination style** — the current design implies **choreography** (each service reacts to the previous service's event, no central coordinator) but this was never decided or written down, and its consequence — compensating actions on failure (e.g., payment fails after inventory was already reserved → inventory must be released) — isn't specified anywhere.
2. **The dual-write problem** — "Order Service publishes `order-created`" (as written) implies writing to Postgres and publishing to Kafka as two separate operations. If the process crashes between them, the order exists but the event never fires, or vice versa. This is a textbook distributed-systems correctness bug, not a hypothetical.
3. **Idempotent consumption** — Kafka is at-least-once delivery by default; nothing in the current docs states that consumers (inventory-service reacting to `order-created`, etc.) must be idempotent against duplicate delivery.

**This is the single most important gap for the "real event-driven distributed system" bar** — the current docs describe the happy path convincingly but don't yet address what makes a distributed system *actually correct* under failure, which is the entire point of calling it "real world."

**Evidence for the standard fix**:
- Saga pattern (choreography vs. orchestration) is the canonical answer to exactly this class of problem: "a sequence of local transactions... if a local transaction fails, the saga executes a series of compensating transactions." ([microservices.io — Saga](https://microservices.io/patterns/data/saga.html))
- Choreography is explicitly the right fit for a design like this one's: "suitable when there are only a few participants in the saga, and you need a simple implementation with no single point of failure" — matches this project's ~3-participant order flow and its simplicity principle. Orchestration trades that simplicity for centralized visibility. ([Conduktor — Saga Pattern](https://www.conduktor.io/glossary/saga-pattern-for-distributed-transactions))
- The dual-write problem has a standard, well-evidenced fix: the **Transactional Outbox pattern** — "writing each event to an outbox table inside the same database transaction as the business data it describes... a separate process then reads that table and forwards the events to a message broker," which "removes the dual-write problem" entirely because the DB write and the event commit atomically together. ([Conduktor — Outbox Pattern](https://www.conduktor.io/glossary/outbox-pattern-for-reliable-event-publishing))

**This needs a decision, not a unilateral fix — see Decision Point 1 below.**

## Conflict #1 (real): Eureka + Config Server become redundant on Kubernetes, but "environment parity" says keep them

**Finding**: `05-technology-architecture.md` states Compose and K8s should be "kept structurally parallel... divergence... is treated as a defect, not an accepted gap." That principle was applied without noticing it collides with Kubernetes' own capabilities: **once running in K8s, Eureka and Spring Cloud Config Server duplicate functionality Kubernetes already provides natively** (Service objects for discovery, ConfigMaps/Secrets for config) — carrying two extra stateful-ish components into production for something the platform already does.

**Evidence**: "The rationale is that Kubernetes already provides a built-in Service Registry, making traditional service discovery tools like Eureka redundant... Eureka and Spring Cloud Config can be considered redundant when running on Kubernetes." Spring's own `spring-cloud-kubernetes` project exists specifically to let services swap Eureka/Config Server clients for K8s-native equivalents. ([Spring Cloud Kubernetes reference](https://cloud.spring.io/spring-cloud-kubernetes/reference/html/), [BitInit — eureka-on-kubernetes](https://github.com/BitInit/eureka-on-kubernetes/blob/master/README_en.md))

**This is a genuine tension, not a one-sided fix**: dropping Eureka/Config Server in K8s removes 2 of the current 11 planned infrastructure components (a real simplicity win, directly serving the "not complicated" instruction) — but it means Compose and K8s would run on *different* discovery/config mechanisms, which is exactly the divergence `05-technology-architecture.md` currently says to avoid. **See Decision Point 2 below.**

## Conflict #2 (real): Redis for gateway rate limiting vs. the already-present Resilience4j RateLimiter

**Finding**: The gateway's draft config wires a Redis-backed token-bucket rate limiter (ADR-0005 inherited this from the original draft YAML without re-examining it). But Resilience4j — already a dependency, already the resilience mechanism of choice per `05-technology-architecture.md` — has its own `RateLimiter` module. Adding Redis is a real infrastructure cost (component #10 of 11) that may not be earning its keep.

**Evidence**: "For a single instance, Resilience4j RateLimiter is typically sufficient... if your service is elastic with a varying number of instances... you would need a rate limiter that maintains its data in a distributed cache" like Redis, "though this comes with performance tradeoffs." ([reflectoring.io — Resilience4j rate limiting](https://reflectoring.io/rate-limiting-with-resilience4j/), [INNOQ — distributed rate limiting](https://www.innoq.com/en/blog/2024/03/distributed-rate-limiting-with-spring-boot-and-redis/))

**The deciding factor is whether the API Gateway runs as a single replica or is horizontally scaled.** With one gateway instance, Resilience4j's in-memory limiter is strictly simpler and removes a component. With multiple replicas, in-memory limiting is *wrong* (each replica enforces its own separate limit, silently multiplying the effective rate cap by replica count) and Redis becomes necessary, not optional. **See Decision Point 3 below.**

## What Did Not Need Changing

- The dual-broker Kafka+RabbitMQ decision (ADR-0003) survives review: it's explicitly user-directed, each broker is doing work the other genuinely isn't suited for, and removing either one would either lose replay/broadcast semantics (Kafka) or force awkward DLQ modeling on Kafka (RabbitMQ). Kept as-is.
- The zero-trust decision (ADR-0002) survives review: it's the one decision in this whole set that already went through adversarial reconsideration twice, and its evidence trail is the strongest in the document set.
- Postgres schema-per-service (ADR-0004) and the API Gateway defense-in-depth boundary (ADR-0005, independent of the Redis question above) both hold up under review — no conflict found.

## Decision Points for the User

Recorded here rather than decided unilaterally, per instruction. Once decided, each becomes ADR-0007/0008/0009 and the relevant architecture docs are updated to match — not left inconsistent with what's actually built.

1. **Distributed transaction pattern**: Choreography-based Saga + Transactional Outbox + idempotent consumers (industry-standard fit for this design's scale and the existing "no central coordinator" implication) — or Orchestration-based Saga (a dedicated coordinator service, more visibility/testability, more moving parts) — or accept the simpler at-least-once/no-outbox risk for now and revisit later (fastest to build, real correctness gap under failure).
2. **Eureka/Config Server in Kubernetes**: keep them in K8s too, for environment parity with Compose (simpler mental model, 2 extra always-on components in production) — or drop them in the K8s phase only in favor of native K8s Service discovery + ConfigMaps/Secrets (leaner production footprint, one deliberate, documented environment divergence).
3. **Gateway rate limiting backend**: Resilience4j in-memory (assumes single gateway replica; removes Redis entirely) — or Redis-backed (required if the gateway will run multiple replicas for availability).

## Related

- Gap #1 → will become ADR-0007 once decided
- Conflict #1 → will become ADR-0008 once decided
- Conflict #2 → will become ADR-0009 once decided (may fold into ADR-0005's next revision instead of a new ADR, depending on which option is chosen)
