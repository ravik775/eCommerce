# ADR-0040: Data classification and retention policy

**Status**: Accepted
**Date**: 2026-08-16
**Deciders**: Solution/Security Architect

## Context

ADR-0037's SOC 2 mapping named two Confidentiality/Privacy gaps with nothing behind them: no data classification (what's "confidential" vs not was never written down), and no retention policy (how long data is kept, and what gets pruned). Both are cheap to write down and give real teeth to decisions already made elsewhere (the `AuditLogger` PII guard added this session, the outbox-table growth noted in ADR-0036).

## Data classification

| Class | Examples in this system | Handling requirement |
|---|---|---|
| **Restricted** (would cause real harm if exposed) | Keycloak client secrets, Postgres/Kafka credentials, SPIFFE SVID private keys | Never logged (enforced structurally now via `AuditLogger`'s key-name guard for the fields it covers); sealed via Bitnami Sealed Secrets at rest (ADR-0014, ADR-0012 in `doc/architecture/`); never appear in this repo's git history going forward (gitleaks CI gate + local script, ADR-0038) |
| **Confidential** (PII / account data) | User email, name, order history, shipping/billing details if ever added | Not logged in plaintext (verified this session — no email/name found in any log statement); access gated by RBAC (ADR-0025); the one confirmed leak (`DuplicateEmailException` embedding email in an HTTP response) fixed this session |
| **Internal** (business data, not directly identifying) | Product catalog, inventory counts, order line items, correlation/trace IDs | Access gated by RBAC per role (CATALOG_ADMIN, INVENTORY_ADMIN, etc., ADR-0033); no special handling beyond normal RBAC |
| **Public** (no confidentiality requirement) | Published product listings visible to any authenticated CUSTOMER, service health/readiness endpoints | No additional control needed |

This classification isn't new policy invented from nothing — it names what the codebase's actual RBAC roles (ADR-0025, ADR-0033) and this session's `AuditLogger`/`DuplicateEmailException` fixes already enforce in practice. Writing it down means a future change can be checked against it ("does this new field belong in Confidential or Internal?") instead of re-deriving the reasoning each time.

## Retention policy

| Data | Current behavior | Decision |
|---|---|---|
| Order/payment/inventory business records (Postgres) | Retained indefinitely, no deletion job exists | **Keep indefinitely.** ADR-0036's capacity calculation shows 5-year growth stays under 100MB — there is no storage-cost or performance reason to prune, and order history has ongoing business value (ADR-0031, order history view) |
| `outbox_event` table (order/inventory/payment-service) | Retained indefinitely after successful relay — flagged as unbounded growth in ADR-0036's follow-up | **Prune after 30 days past successful relay.** No business value in an already-relayed outbox row past that point; 30 days gives ample margin for any manual replay/debugging need. Not yet implemented — tracked as a follow-up below, same low-urgency tier as ADR-0036's other follow-ups given the current absolute volume is negligible |
| Application/audit logs (stdout → `kubectl logs`) | Bounded by each pod's own log rotation, not by this application; Loki (if/when used to aggregate) would set its own retention | No new decision needed here — this is infrastructure-layer retention, not application-layer, and already has a natural bound |
| Trace data (Tempo) | 6h block retention (ADR-0032) | Already explicitly decided; no change |
| Metrics (Prometheus) | 6h retention (`k8s/base/prometheus.yaml`) | Already explicitly decided; no change |
| Postgres backups (`pg_dump` CronJob, ADR-0034) | 14-day retention | Already explicitly decided; no change |
| A user's own account data, on request | **No process exists today** | See Follow-up — this is the one real, unclosed Privacy gap (data-subject access/erasure request handling) |

## Decision

Adopt the classification table above as the system's data-handling reference, and the retention table as the system's stated retention policy — both largely **codifying decisions already made** rather than introducing new ones, with two explicit exceptions requiring follow-up work.

## Consequences

- Positive: closes the "no documented classification/retention" gap ADR-0037 named, at near-zero cost — most of this was already true in practice, just unwritten.
- Negative / accepted trade-off: this is a policy document, not enforcement — nothing technical stops a future field from being misclassified. The `AuditLogger` key-name guard (this session) is the one place classification is actually enforced in code; everything else relies on developers reading and following this document.
- Follow-up required: (1) implement the 30-day `outbox_event` pruning job — low urgency given current negligible volume (ADR-0036), but a real gap; (2) design a data-subject access/erasure process — currently nothing exists for a user to request their data or request deletion, which is a real Privacy-criterion gap with no current implementation, appropriately scoped as future work rather than blocking anything today since this is an internal tool without an active external privacy-rights obligation (no GDPR/CCPA-covered user base confirmed).

## Related

- Related: ADR-0037 (SOC 2 mapping — the gap this ADR closes), ADR-0025/ADR-0033 (RBAC — what the classification table names), ADR-0036 (capacity planning — the outbox growth this ADR's retention decision responds to), this session's `AuditLogger`/`DuplicateEmailException` fixes (the enforcement mechanism for the Confidential/Restricted classes)
