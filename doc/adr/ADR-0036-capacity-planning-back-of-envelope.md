# ADR-0036: Capacity planning — back-of-envelope sizing for 100 users / 20 active / 10,000 products, 5-year horizon

**Status**: Accepted
**Date**: 2026-08-16
**Deciders**: Solution/Security Architect

## Context

The system's stated design target — 100 internal users, ~20 concurrently active, ~10,000 products today — has never been translated into a throughput/storage estimate or checked against the actual resource allocation in `k8s/base/*.yaml` and the namespace `ResourceQuota`/`LimitRange` (`k8s/base/resource-limits.yaml`). Without this, "is the current sizing enough?" has no documented answer, and any future request to scale replicas or bump resource requests has no baseline to compare against.

This is an internal tool, not a consumer-facing storefront — the relevant 5-year growth axis is **catalog size and data volume**, not user count (100 internal users is a roughly fixed ceiling tied to headcount, not organic growth).

## Evidence

All figures below are order-of-magnitude estimates, not load-tested measurements — flagged as such throughout. Confirmed inputs, from the codebase (see ADR-0034 for the source review):

- Each of the 7 backend Spring Boot services: `requests: 150m CPU / 256Mi`, `limits: 500m CPU / 512Mi` (`k8s/base/{order,catalog,inventory,payment,notification,user}-service.yaml`, `api-gateway.yaml`).
- Namespace `ResourceQuota` (`k8s/base/resource-limits.yaml`): `requests.cpu: 4`, `requests.memory: 5Gi`, `limits.cpu: 8`, `limits.memory: 8Gi`, `pods: 30`. Comment there confirms this was sized for a local Docker Desktop VM (~7.6Gi total), not a cloud node — a real deployment target has no comparable ceiling.
- Current CPU request sum across all running workloads (7 backend services × 150m + Kafka 300m + Redis 50m + RabbitMQ 250m + Keycloak 200m + Postgres 150m) ≈ **2.0 of the 4-core requests quota** — roughly 2x headroom remains in the *current* local-dev quota before it would need raising, before even considering a real (non-local) deployment target.

## Back-of-envelope calculations

**Traffic**: 20 active users, internal-tool usage pattern (not continuous polling) — assume each active user issues on the order of 1 request per 5–10 seconds while actively using the app (browsing, adding to cart, checking status), i.e. ~0.15–0.2 req/s/user.
- Sustained load: 20 × 0.2 ≈ **4 req/s** system-wide.
- Generous peak burst (e.g. several users checking out simultaneously): estimate 5–10x sustained ≈ **20–40 req/s** momentarily.
- A single Spring Boot CRUD endpoint on a 0.5-core container conservatively handles tens to low-hundreds of req/s for this kind of workload (general industry rule of thumb, not benchmarked in this repo) — **the peak estimate above is roughly 1–2 orders of magnitude below what the current per-service CPU limit (500m) can sustain.** Compute is not a constraint at this user count, today or across the 5-year horizon, provided user count stays near the stated 100/20 ceiling.

**Order volume**: internal tool, low conversion — estimate ~20 active users × ~5 sessions/week × ~10% of sessions resulting in an order ≈ 10 orders/week ≈ **~1.5 orders/day**, ≈ **~2,600 orders over 5 years**.
- Orders/order-items table growth: 2,600 orders × ~5 line items avg × ~200 bytes/row ≈ **~2.6 MB** over 5 years. Negligible.
- Outbox/audit event volume (ADR-0007, ADR-0016): each order triggers on the order of 5–10 saga/audit events; 2,600 orders × 8 events × ~500 bytes ≈ **~10 MB** over 5 years, before any pruning. Still negligible against the current 2Gi Postgres PVC, but see Follow-up below — nothing currently prunes the `outbox_event` table after successful relay.

**Catalog growth**: 10,000 products today; even an aggressive 20%/year compounding growth assumption → 10,000 × 1.2⁵ ≈ **~25,000 products** in 5 years.
- Product row footprint (name, description, price, category, provider ref, status) ≈ 1KB/row generously → 25,000 × 1KB ≈ **~25 MB**. Negligible; product images/media, if ever added, are the only plausible driver of real storage growth, and none exists in this system today (confirmed no image/blob storage in the schema).

**Total Postgres storage projection, 5 years**: catalog (~25MB) + orders/items (~2.6MB) + outbox/audit (~10MB, unpruned) + users/inventory/misc (low single-digit MB) ≈ **well under 100MB of actual business data**, against a provisioned 2Gi PVC — **roughly 20x headroom**, even before considering Postgres's own overhead (indexes, WAL, etc., which will dominate the *actual* disk usage number far more than row data at this scale).

## Decision

**Current resource allocation (CPU/memory requests+limits, single replica per service, 2Gi Postgres PVC) is more than sufficient for the stated 5-year horizon at 100 users / 20 active / ~25,000 products.** No proactive scaling action is needed on account of raw throughput or storage capacity. The system's real constraints (documented separately) are **availability** (single replica = no failover, ADR-0034) and **local-dev-only** resource quota ceilings (irrelevant to a real deployment target on standard cloud node sizing) — not capacity in the traffic/storage sense evaluated here.

## Consequences

- Positive: closes a real documentation gap — future "should we scale this?" questions have a written baseline instead of guesswork, and the answer for the stated user/product scale is a confident "no, not for capacity reasons."
- Negative / accepted trade-off: all figures here are estimates, not load-tested — no load test has ever been run against this system (confirmed absent from the CI pipeline and `doc/`). The 1–2 orders-of-magnitude headroom claimed above is a reasonable buffer against estimation error, but isn't a substitute for an actual load test before any real-user-facing launch.
- Follow-up required: (1) load testing to validate the "compute isn't the constraint" claim empirically rather than by estimate alone — **deferred as a deliberate decision, not an oversight; see ADR-0041** for the full reasoning (small fixed user base, wide estimated safety margin, availability not throughput is the actual binding constraint) and the base strategy (k6, target scenarios) to use once a trigger condition there is met; (2) add a retention/archival policy for the `outbox_event` table — **closed, see ADR-0040** (30-day post-relay pruning decision); (3) re-run this estimate if the user-count assumption changes (e.g. this tool is ever opened to external/customer users instead of staying internal) — that would invalidate the "20 active users" traffic assumption this whole document rests on.

## Related

- Related: ADR-0034 (backup/replication posture — the availability half of capacity planning), ADR-0022 (Postgres StatefulSet sizing), ADR-0040 (data retention — closes follow-up #2), ADR-0041 (load testing — closes follow-up #1 as a deferral decision)
