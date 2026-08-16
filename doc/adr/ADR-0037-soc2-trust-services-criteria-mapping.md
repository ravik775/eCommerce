# ADR-0037: SOC 2 Trust Services Criteria — current-state mapping and gaps

**Status**: Accepted
**Date**: 2026-08-16
**Deciders**: Solution/Security Architect

## Context

No document anywhere in this repo maps the system's actual controls against SOC 2's Trust Services Criteria (TSC). That's a real gap for any organization that might ever need to answer "are we SOC 2 ready" — not because this internal, 20-active-user tool needs a SOC 2 report today, but because the gap between "we have good security practices" and "we can point an auditor at evidence for each TSC point" is exactly the kind of thing that's expensive to discover for the first time under audit pressure. This ADR is an honest, evidence-based mapping, not a compliance claim — nothing here should be read as "this system is SOC 2 compliant."

## Evidence

Mapped against the five TSC categories (Security is the only one mandatory for any SOC 2 report; the other four are opt-in based on service commitments).

### Security (the mandatory "Common Criteria")

| Control area | Current state | Evidence |
|---|---|---|
| Logical access control | RBAC via Keycloak JWT roles, method-level `@PreAuthorize`, gateway-level `RequireRoleGatewayFilterFactory` | ADR-0025, ADR-0028, ADR-0033 |
| Network segmentation | Default-deny NetworkPolicy + explicit per-service allow rules | `k8s/base/networkpolicy.yaml`; **gap**: local-dev deviation grants broad egress to a host-gateway IP for Postgres/Kafka/Keycloak (self-documented in the file, not hidden) |
| Encryption in transit (service-to-service) | SPIFFE/SPIRE mTLS, enabled on all 7 backend services, both inbound and outbound | ADR-0002/0006, `common-lib/.../spiffe/*` |
| Encryption in transit (edge) | **Gap**: no Ingress/TLS-termination layer exists at all in `k8s/base/` — the gateway is reached over plain HTTP in this local deployment | No Ingress manifest found in the repo |
| Secrets management | Bitnami Sealed Secrets (ciphertext at rest in git, decrypted only by the in-cluster controller) | `k8s/base/secrets.yaml` |
| Vulnerability management | SAST (CodeQL), SCA (OWASP Dependency-Check, fails build at CVSS ≥ 8), secret scanning (gitleaks) all run in CI on every push | `.github/workflows/ci.yml`, ADR-0010; **gap**: DAST (ZAP baseline) is advisory-only (`continue-on-error: true`), and container image scanning (Trivy) is documented in ADR-0019 as a decision but was never actually wired into CI |
| Change management | Every architectural decision requires a written ADR before implementation (this document is an instance of that practice); CI gates on SAST/SCA/secret-scan before merge | This repo's own TOGAF-driven ADR discipline |
| Audit logging | Structured `AuditLogger` for security-relevant events (login, access-denied, order/payment lifecycle) | ADR-0016, `common-lib/.../audit/AuditLogger.java` |
| Perimeter defense (WAF) | **Gap**: ADR-0013 documents a ModSecurity/OWASP CRS decision that was never implemented — no Ingress controller exists to attach it to | ADR-0013 vs. actual `k8s/base/` contents |

### Availability

| Control area | Current state |
|---|---|
| Redundancy | **Gap**: every service, including Postgres/Kafka/Redis, runs a single replica (see ADR-0034) — no failover for any component |
| Backup/recovery | Postgres: nightly `pg_dump`, 14-day retention, untested restore procedure (ADR-0034). Kafka/Redis: none, accepted as low-consequence per ADR-0034's reasoning |
| Monitoring/alerting | Prometheus + Grafana dashboards, OTel traces (ADR-0032) exist; **gap**: no alerting rules were found configured (dashboards are pull-based/manual-observation only, no Alertmanager or paging integration) |
| Defined RTO/RPO | **Gap**: none exist anywhere in the documentation (explicitly named as a gap in ADR-0034) |

### Processing Integrity

| Control area | Current state |
|---|---|
| Idempotent processing | Hybrid key+payload-hash idempotency on saga consumers | ADR-0024 |
| Distributed transaction safety | Outbox pattern avoids dual-write inconsistency between DB and Kafka | ADR-0007 |
| Failure isolation | Circuit breaker on order→payment call path | Referenced throughout this session's testing (`orderCircuit`) |
| Input validation | Bean Validation (`@Valid`) on request DTOs across services (not independently re-verified in this pass — asserted from prior session context, not fresh evidence) | — |

### Confidentiality

| Control area | Current state |
|---|---|
| Data classification | **Gap**: no documented data classification (what's "confidential" vs "public" was never formally defined) |
| Access scoping | Schema-per-service Postgres isolation (ADR-0004) limits blast radius of a single service's compromise | ADR-0004 |
| PII in logs | Reviewed this session: no card numbers, CVVs, raw JWTs, or emails found in actual log/audit statements — one real leak found and fixed (`DuplicateEmailException` was embedding the raw email into an HTTP 409 response body, also an account-enumeration oracle) | This session's fix, `user-service/.../DuplicateEmailException.java` |
| Log redaction framework | **Gap**: `AuditLogger` has no field allowlist/denylist — nothing stops a future caller from passing an email or address into it; today's cleanliness is a property of current call sites, not an enforced guarantee | `common-lib/.../audit/AuditLogger.java` |

### Privacy

| Control area | Current state |
|---|---|
| Data subject rights (access/erasure) | **Gap**: no documented or implemented process for a user to request their data or request deletion |
| Retention policy | **Gap**: no defined retention period for user/order data; `outbox_event`/audit tables are unpruned (also noted in ADR-0036) |
| Consent/purpose limitation | Google OAuth login collects only `preferred_username`/roles via the identity broker (ADR realm config) — no evidence of collecting beyond what's operationally needed, but this was never written down as a stated privacy principle |

## Decision

Adopt this document as the system's SOC 2 baseline: it is **not** a compliance certification, and several TSC areas (Availability's RTO/RPO, Privacy's data-subject-rights process, Confidentiality's data classification) are **explicit, named gaps**, not silently absent. This is the intended state for a 20-active-user internal tool with no external customer commitments today — the mapping exists so that the *moment* an external commitment (a customer contract requiring SOC 2, a real GDPR/CCPA obligation) appears, there's a concrete gap list to execute against instead of starting from zero.

## Consequences

- Positive: converts "probably fine" into a specific, auditable list of what's real and what's missing, organized the way an actual SOC 2 audit would ask for it.
- Negative / accepted trade-off: writing this down makes the gaps visible and citable — that's the point, but it does mean this document itself becomes the thing an auditor or security reviewer would read first and hold the team to.
- Follow-up required, roughly in priority order: (1) TLS termination at the edge — the biggest concrete gap, since plaintext HTTP for the gateway is a real exposure the moment this isn't purely local; (2) Trivy image scanning, since ADR-0019 already decided to do this and it simply wasn't wired in; (3) an `AuditLogger` field guard (allowlist or a warning on suspicious field names like "email"/"address") so Confidentiality's log-cleanliness isn't purely accidental; (4) a written data retention policy, even a simple one, for Privacy.

## Related

- Related: ADR-0010 (SAST/DAST), ADR-0013 (WAF, undelivered), ADR-0016 (audit logging), ADR-0019 (image scanning, undelivered), ADR-0024 (idempotency), ADR-0025 (RBAC), ADR-0034 (backup/replication)
