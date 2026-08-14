# ADR-0004: Data store — PostgreSQL, schema-per-service

**Status**: Accepted
**Date**: 2026-08-12
**Deciders**: Solution/Security Architect

## Context

Requirement #5: use Postgres as backend storage, hosted locally within the Kubernetes cluster. The existing (unused) config-server drafts had each service pointed at MySQL with hardcoded `root/root` credentials and no migration tooling — neither the engine nor the ownership model matched the target.

## Options Considered

| Option | Pros | Cons |
|---|---|---|
| One shared Postgres database, shared tables across services | Simplest to stand up initially | Violates service autonomy — any service can read/write another's data, no clear ownership, schema changes require cross-team coordination; direct contradiction of the microservices premise this project is built on |
| One Postgres database per service (separate DB instances/servers) | Strongest isolation | Highest operational cost for a project this size — that many separate database servers to run/patch/back up in a single local K8s cluster is disproportionate |
| One Postgres instance, one schema per service (schema-per-service) | Each service owns its schema, connection, and migrations; other services must go through the owning service's API or consumed events, never direct DB access; single database server to operate | Slightly less isolation than fully separate DB servers (accepted — namespace-level and RBAC controls compensate, and this is the documented mainstream middle ground) |

## Evidence

- The canonical reference for this space (Chris Richardson's microservices.io, cited across virtually every serious microservices architecture discussion) documents exactly this trade-off: "there are a few ways to implement... Private-tables-per-service... Schema-per-service... Database-server-per-service. Using a schema per service is appealing since it makes ownership clearer" while remaining cheaper to operate than a full server-per-service split. ([microservices.io — Database per Service](https://microservices.io/patterns/data/database-per-service.html))
- Same source: "If Service B needs data that lives in Service A, it must ask Service A for it through a well-defined API or consume events that Service A publishes" — this directly informs the Kafka event-consumption pattern in ADR-0003 (e.g., inventory-service must not query order-service's tables directly, it reacts to `order-created`).
- Documented benefit directly applicable here: schema changes deploy independently per service team without cross-service coordination — relevant because this project has 5 data-owning services (user, catalog, inventory, order, payment) that will evolve independently.

## Decision

Single PostgreSQL instance hosted in-cluster (Kubernetes namespace `ecom`), with one schema per data-owning service (`user_service`, `catalog_service`, `inventory_service`, `order_service`, `payment_service`). Each service owns its schema's migrations (Flyway) and connection credentials; no service is granted cross-schema access. No plaintext credentials in committed config — moved to Kubernetes Secrets by Phase 7.

## Consequences

- Positive: clear data ownership per service, matches the microservices premise; single database server to operate/back up rather than five.
- Negative / accepted trade-off: less physical isolation than database-server-per-service — acceptable for this project's scale, revisit if any single service's load/compliance needs outgrow shared-instance operation.
- Follow-up required: decide StatefulSet-vs-operator (e.g., CloudNativePG) for the in-cluster Postgres instance before Phase 6 (tracked as an open decision in the delivery plan).

## Related

- Related architecture doc: `doc/architecture/03-data-architecture.md`
