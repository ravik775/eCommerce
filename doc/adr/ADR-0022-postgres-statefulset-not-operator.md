# ADR-0022: Postgres in-cluster deployment — plain StatefulSet + PVC (not an operator)

**Status**: Accepted
**Date**: 2026-08-13
**Deciders**: Solution/Security Architect

## Context

ADR-0004 committed to Postgres in-cluster but deferred the exact mechanism, tracked as an open decision in `doc/architecture/07-migration-planning.md`.

## Options Considered

| Option | New infrastructure? | Capability |
|---|---|---|
| Plain Kubernetes `StatefulSet` + `PersistentVolumeClaim` | None beyond native K8s primitives | Stable identity, persistent storage; backup/restore and failover are manual/scripted |
| An operator (e.g., CloudNativePG) | Yes — a controller Deployment + CRDs to manage | Automated backups, point-in-time recovery, failover/replica management, rolling upgrades handled declaratively |

## Decision

**Plain `StatefulSet` + `PersistentVolumeClaim`** for now. Consistent with every other "don't add infrastructure before it's needed" call in this project (ADR-0002, ADR-0013, ADR-0014, ADR-0015, ADR-0021) — a single-instance, locally-hosted Postgres (per requirement #5's own framing: "hosted locally within K8s cluster") doesn't yet need an operator's HA/failover machinery. Backups are handled via a scheduled `pg_dump` CronJob to start.

## Consequences

- Positive: zero new controller/CRDs to operate; matches the project's consistent simplicity bias.
- Negative / accepted trade-off: no automated point-in-time recovery or failover — a real gap if this Postgres instance is ever expected to survive a node failure without manual intervention or lose zero data on crash. This is the clearest "revisit later" candidate in the whole design: CloudNativePG (or a managed cloud Postgres, if the constraint of self-hosting ever relaxes) is the documented upgrade path the moment backup/HA becomes a real operational requirement, not a hypothetical one.
- Follow-up required: define the `pg_dump` CronJob's schedule and retention when Phase 6 provisions Postgres; document the manual restore procedure since there's no operator to do it declaratively.

## Related

- ADR-0004 (Postgres, schema-per-service) — this closes that ADR's open follow-up item
- `doc/architecture/07-migration-planning.md`, Phase 6
