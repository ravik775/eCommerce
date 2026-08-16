# ADR-0034: Backup and replication posture across all stateful stores (Postgres, Kafka, Redis)

**Status**: Accepted
**Date**: 2026-08-16
**Deciders**: Solution/Security Architect

## Context

ADR-0022 documented Postgres's single-instance risk and promised a `pg_dump` CronJob "when Phase 6 provisions Postgres" — that follow-up was never actually implemented (no CronJob existed in `k8s/base/` until this ADR closes it). Kafka and Redis have **no** equivalent statement anywhere: both run as single-replica, unbacked-up, non-persistent (Redis) or single-broker/no-PVC (Kafka) deployments, and that was previously an *undocumented* gap rather than an accepted one. A system review should not have to reverse-engineer "is this intentional?" from the absence of a CronJob — this ADR makes the posture explicit for all three stores in one place, closing both the missing-implementation and the missing-documentation gap together.

Confirmed current state (`k8s/base/*.yaml`):

| Store | Replicas | Persistence | Backup |
|---|---|---|---|
| Postgres | 1 (StatefulSet) | 2Gi PVC, `ReadWriteOnce` | **Now**: nightly `pg_dump` CronJob → separate PVC, 14-day retention (`postgres-backup-cronjob.yaml`) |
| Kafka | 1 (plain Deployment, KRaft, no Zookeeper) | **None** — no PVC/volumeClaimTemplate at all | None |
| Redis | 1 (plain Deployment) | **None** — no AOF/RDB, no PVC | None |

## Options Considered

| Option | New infrastructure? | Capability |
|---|---|---|
| Leave Kafka/Redis exactly as-is, document the acceptance | None | Matches actual data criticality (see below); zero added ops burden |
| Add PVC + persistence to Kafka and Redis | Some (volumeClaimTemplates, Redis `appendonly yes`) | Survives pod restart, doesn't survive the loss this system actually cares about |
| Multi-broker Kafka / Redis Sentinel or Cluster | Yes — real HA topology, multiple pods, quorum logic | Survives single-node failure; the kind of investment this project has consistently deferred (ADR-0002, ADR-0013, ADR-0014, ADR-0015, ADR-0021, ADR-0022) until the need is real, not hypothetical |

## Evidence

- General practice, not a benchmarked source: Kafka topics in this system are **transport for the outbox saga pattern** (ADR-0007), not a system of record — every event published to Kafka is *also* durably written to Postgres first (the outbox table) before being relayed. Losing Kafka's in-flight/retained messages loses at most the propagation of already-durable Postgres state to downstream services, which the outbox poller re-publishes on Kafka's return (subject to the poller's own retry logic) or which can be manually replayed from the outbox table if needed. This makes Kafka's own persistence meaningfully less critical than Postgres's.
- Redis in this system is used only as the API gateway's rate-limit token bucket (`GatewayConfig.java`, per ADR-0009). Losing Redis state resets every client's rate-limit window to full — a availability/fairness blip, not a correctness or data-loss issue. No session state, no cache of authoritative data, lives in Redis.

## Decision

**Postgres**: nightly `pg_dump` CronJob (implemented — `k8s/base/postgres-backup-cronjob.yaml`), 14-day retention, same-cluster PVC. This is a crash/accidental-delete safety net, explicitly **not** point-in-time recovery and **not** off-cluster/off-node durability — a full node or cluster loss still loses both primary and backup together.

**Kafka**: no backup, no persistence, single broker — accepted as-is. Justified by the outbox pattern making Kafka transport-only, not a system of record (see Evidence).

**Redis**: no backup, no persistence, single instance — accepted as-is. Justified by its sole use as an ephemeral rate-limit counter with no correctness impact on loss.

## Consequences

- Positive: the actual risk posture now matches a written decision instead of silence; Postgres — the one store that genuinely holds authoritative business data (orders, users, inventory counts, catalog) — has a real backup where it had none before.
- Negative / accepted trade-off: **no RTO/RPO target is defined anywhere in this system.** A full node/cluster loss today means: Postgres data loss bounded by "up to 24h since last nightly dump" (RPO ≈ 24h) with restore time entirely manual and untested (RTO ≈ unknown — no restore drill has ever been run); Kafka/Redis state loss is immediate and total but low-consequence per the Evidence above. This is acceptable for a 100-user/20-active-user internal system with no defined SLA, but would need re-litigating the moment any SLA or compliance commitment (e.g. a customer-facing uptime promise) is made.
- Follow-up required: (1) actually run a restore drill from a `pg_dump` backup at least once — an untested backup is not a verified backup; (2) if this system ever needs an RPO tighter than "up to 24h," the documented upgrade path is CloudNativePG (per ADR-0022) with continuous WAL archiving, not a shorter cron interval on plain `pg_dump`.

## Related

- Supersedes/closes the follow-up in: ADR-0022 (Postgres StatefulSet, not an operator)
- Related: ADR-0007 (saga/outbox pattern — why Kafka doesn't need its own durability), ADR-0009 (Redis rate limiter — why Redis doesn't need its own durability)
- Implementation: `k8s/base/postgres-backup-cronjob.yaml`
