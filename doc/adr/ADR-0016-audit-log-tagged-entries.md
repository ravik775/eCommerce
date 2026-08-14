# ADR-0016: Audit log storage — tagged entries in the centralized log stack

**Status**: Accepted
**Date**: 2026-08-13
**Deciders**: Solution/Security Architect

## Context

SOC2-style audit requirements (login events, order/payment actions) need a defined storage approach. Weighed a separate tamper-evident audit store against reusing the centralized logging pipeline already planned for Phase 7.

## Options Considered

| Option | New infrastructure? | Tamper resistance |
|---|---|---|
| Structured log entries tagged `audit=true`, shipped through the existing centralized logging pipeline (Phase 7) | None | Weaker — anyone with log-write access on a service could theoretically alter an entry before it ships |
| Separate append-only audit store (e.g., a Postgres table with no `UPDATE`/`DELETE` grants, or WORM storage) | Yes — a distinct data path, its own access-control surface, its own retention policy to design | Stronger — structurally harder to alter after the fact |

## Decision

Audit-relevant events (login, order placed, payment processed/refunded, admin actions) are emitted as structured log entries with a distinct `audit=true` marker and a stable schema (actor, action, resource, timestamp, outcome), flowing through the same centralized logging pipeline as all other application logs (Phase 7, `doc/architecture/07-migration-planning.md`). No separate audit datastore is introduced.

## Consequences

- Positive: zero new infrastructure component; audit events are queryable with the same tooling (Grafana/log aggregation) as everything else, and the "audit trail exists and is queryable" Phase 7 DoD item is satisfiable with this approach.
- Negative / accepted trade-off: no structural tamper-evidence beyond whatever the logging pipeline's own access controls provide — a genuinely append-only, permission-restricted audit store is stronger. Revisit if a compliance requirement beyond a SOC2 baseline (e.g., a formal audit requiring non-repudiation) ever applies.
- Follow-up required: define the audit event schema and the `audit=true` tagging convention as part of Phase 7 implementation.

## Related

- `doc/architecture/07-migration-planning.md`, Phase 7
- `00-preliminary.md`'s SOC2-alignment principle
