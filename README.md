# eCommerce Platform

A microservices-based eCommerce system — Spring Boot 3.5 / Java 21 services behind a Spring Cloud Gateway, Keycloak-brokered identity, Kafka-driven saga orchestration, and a zero-trust Kubernetes runtime. Designed and delivered under a TOGAF 10 ADM discipline: every non-trivial technology decision has a written, evidence-backed [Architecture Decision Record](doc/adr/) — 42 at last count — rather than being implied by the code.

This README is written for a technical reviewer forming a first impression of the architecture. It states what's actually built and verified, names the real gaps rather than hiding them, and points to the ADR that has the full evidence trail for every claim below.

## Table of Contents

- [Architecture at a Glance](#architecture-at-a-glance)
- [Security Architecture](#security-architecture)
- [Observability Architecture](#observability-architecture)
- [Architectural Decisions Worth Reading First](#architectural-decisions-worth-reading-first)
- [Code Quality](#code-quality)
- [Compliance Posture](#compliance-posture)
- [Capacity & Scale](#capacity--scale)
- [Full Documentation Index](#full-documentation-index)

## Architecture at a Glance

| Layer | Technology | Decision record |
|---|---|---|
| Language / runtime | Java 21, Spring Boot 3.5 | — |
| API Gateway | Spring Cloud Gateway (reactive/WebFlux) | [ADR-0005](doc/adr/ADR-0005-api-gateway-boundary.md) |
| Identity Provider | Keycloak, brokering Google as federated IdP | [ADR-0001](doc/adr/ADR-0001-idp-keycloak.md) |
| Zero-trust service identity | SPIRE (SPIFFE X.509-SVID), application-level mTLS — **no service mesh** | [ADR-0002](doc/adr/ADR-0002-zero-trust-spire-app-level.md) |
| Datastore | PostgreSQL, schema-per-service | [ADR-0004](doc/adr/ADR-0004-datastore-postgres-schema-per-service.md) |
| Domain eventing | Kafka (Choreography Saga + Transactional Outbox) | [ADR-0003](doc/adr/ADR-0003-eventing-kafka-rabbitmq.md), [ADR-0007](doc/adr/ADR-0007-saga-outbox-idempotency.md) |
| Task queues | RabbitMQ | [ADR-0003](doc/adr/ADR-0003-eventing-kafka-rabbitmq.md) |
| Rate limiting | Redis-backed token bucket at the gateway | [ADR-0009](doc/adr/ADR-0009-rate-limiter-redis.md) |
| Observability | Micrometer + Prometheus + Grafana + OpenTelemetry (Tempo) + Loki | [ADR-0032](doc/adr/ADR-0032-opentelemetry-tracing-and-unified-correlation.md) |
| Orchestration | Kubernetes, namespace `ecom`, Pod Security Standard `restricted` | [ADR-0006](doc/adr/ADR-0006-k8s-zero-trust-layers.md), [ADR-0020](doc/adr/ADR-0020-k8s-pod-security-standards.md) |
| Edge / WAF | ingress-nginx with ModSecurity + OWASP Core Rule Set | [ADR-0013](doc/adr/ADR-0013-edge-waf-modsecurity.md) |

**Current deployment scale**: every service runs a single replica in the local reference deployment (not multi-replica HA) — an explicit, documented trade-off for a 100-user / ~20-concurrently-active internal system, not an oversight. See [ADR-0034](doc/adr/ADR-0034-backup-and-replication-posture.md) and [ADR-0036](doc/adr/ADR-0036-capacity-planning-back-of-envelope.md).

## Security Architecture

Security here is layered as genuinely independent controls — a compromise of one layer doesn't imply compromise of the others. This is not a generic "we follow best practices" claim; each layer below names the specific mechanism in place.

### 1. Service-to-service identity — SPIFFE/SPIRE mTLS, not a service mesh
Every one of the 7 backend services presents and verifies an X.509-SVID (SPIFFE Verifiable Identity Document) issued by an in-cluster SPIRE server, enforced at the application layer via a shared `common-lib` module — both **inbound** (Tomcat connector requires a client SVID) and **outbound** (every service-to-service HTTP call presents its own SVID). No Istio, Linkerd, or sidecar proxy: TLS terminates once, in the JVM. This was a **researched reversal** from an initial Linkerd+SPIRE direction, kept visible in [ADR-0002](doc/adr/ADR-0002-zero-trust-spire-app-level.md) rather than silently corrected — the evidence (independently conflicting mTLS latency benchmarks across Istio/Linkerd/Cilium, and industry mesh-adoption trends) is cited with sources, not asserted.

### 2. Network segmentation — Kubernetes NetworkPolicy, default-deny
The `ecom` namespace enforces `podSelector: {}` default-deny on both Ingress and Egress, with explicit per-service allow rules matching the actual call graph — not a permissive baseline with exceptions bolted on. [ADR-0006](doc/adr/ADR-0006-k8s-zero-trust-layers.md). One honestly-documented local-only weakening exists (broad `ipBlock` egress to host-run Postgres/Kafka/Keycloak in this specific local dev environment, since they run as standalone containers, not pods) — the fix is simply deploying the already-written target-state manifests in-cluster, not new engineering; Cilium FQDN-based egress policies were researched as the option if a future deployment genuinely needs an out-of-cluster dependency.

### 3. Identity & access — OIDC/PKCE + JWT RBAC, enforced independently by every service
- Login: Authorization Code + PKCE via Keycloak ([ADR-0017](doc/adr/ADR-0017-oidc-pkce-public-client.md)), Google as a federated identity provider.
- Every service — not just the gateway — independently validates the JWT and enforces role checks via Spring Security method security (`@PreAuthorize`) against Keycloak's `realm_access.roles` claim, defense-in-depth rather than gateway-only trust. [ADR-0025](doc/adr/ADR-0025-jwt-rbac-method-security.md).
- Roles were restructured mid-project from a confusing `ADMIN`/`SUPER_ADMIN` pair into purpose-named `IAM_ADMIN` (role-assignment only, zero operational privilege) and `PLATFORM_ADMIN` (a genuine Keycloak **composite role**, verified live via decoded JWTs to actually expand into its constituent roles at token-issue time) — [ADR-0033](doc/adr/ADR-0033-admin-role-restricted-to-iam-operations-admin-split.md).
- A `CAN_TRACE` role gates a user-facing "force detailed tracing" toggle — role-based access to an observability capability, not just to business data.

### 4. Application security testing — real CI gates, not aspirational documentation
An architecture review this session found two ADRs (WAF, Trivy image scanning) that had been *decided* but never actually implemented — both are now closed and verified live, alongside what was already real:

| Control | Tool | Status |
|---|---|---|
| Secret scanning | gitleaks (`--no-git`, working-tree scan) | Real, CI-gated |
| SAST | CodeQL | Real, CI-gated |
| SCA (dependency CVEs) | OWASP Dependency-Check, fails build at CVSS ≥ 8 | Real, CI-gated |
| Container image scanning | Trivy, matrix over all 10 service images, HIGH/CRITICAL gated | **Implemented 2026-08-16** ([ADR-0019](doc/adr/ADR-0019-container-image-scanning-trivy.md)) — pinned to a verified full commit SHA, not a floating tag, because `trivy-action` itself suffered a real supply-chain compromise in March 2026 |
| DAST | OWASP ZAP baseline | Real, advisory-only (`continue-on-error`) pending a real authenticated-endpoint target |
| WAF | ModSecurity + OWASP Core Rule Set on ingress-nginx | **Implemented 2026-08-16** ([ADR-0013](doc/adr/ADR-0013-edge-waf-modsecurity.md)) — `DetectionOnly` mode during burn-in, traffic flow verified live |
| Local reproducibility | `scripts/run-sast-dast-local.sh` (gitleaks + Semgrep + Dependency-Check + optional ZAP) | Tested live — correctly caught 23 real findings in gitignored local files |

### 5. Secrets management
Bitnami Sealed Secrets — ciphertext committed to git, decryptable only by the in-cluster controller's private key. Not plaintext `kubectl create secret` and not a manual process. [ADR-0014](doc/adr/ADR-0014-secrets-k8s-native.md), `doc/architecture/12-secrets-management.md`.

### 6. Pod-level hardening — Kubernetes Pod Security Standard `restricted`
Every backend Deployment runs non-root, `allowPrivilegeEscalation: false`, all Linux capabilities dropped, **and `readOnlyRootFilesystem: true`** — the last of which was *claimed* in [ADR-0020](doc/adr/ADR-0020-k8s-pod-security-standards.md) but not actually set until this session's review found the gap; closed and verified live across all 7 services (with a scoped writable `/tmp` for Tomcat/SPIFFE SVID material).

### 7. PII handling in logs — a genuinely enforced guard, not a convention
An audit of every log/audit statement across all 7 services found no card numbers, CVVs, raw JWTs, or emails in production log output — but the one confirmed leak (`DuplicateEmailException` embedding a raw email in an HTTP 409 response body, which also doubled as an account-enumeration oracle) was found and fixed, with a regression test. Beyond the fix, `AuditLogger` now redacts any field by **key name** (`email`, `password`, `card`, `token`, `phone`, `address`, etc., case-insensitive substring match) regardless of caller intent — structural enforcement, not just careful callers. See [ADR-0037](doc/adr/ADR-0037-soc2-trust-services-criteria-mapping.md) and [ADR-0040](doc/adr/ADR-0040-data-classification-and-retention.md).

### 8. Payment data — architected to stay out of PCI-DSS scope
`payment-service`'s processors are currently simulated (no real gateway integration), by deliberate decision — see [ADR-0027](doc/adr/ADR-0027-payment-gateway-integration-deferred.md). That ADR already commits to client-side tokenization for whenever real payment integration lands, which is the standard architecture for minimizing PCI-DSS obligations: the application server receives only a token, never raw card data.

## Observability Architecture

### Distributed tracing — OpenTelemetry, with a genuine cross-service span tree
- Every service exports OTel spans via OTLP to Tempo. A custom `Sampler`/exporter pairing (`ErrorAlwaysSampledSpanExporter`) guarantees every span that ends in an HTTP error (4xx **and** 5xx — both status-attribute encodings checked, since Micrometer's tags are string-typed while raw OTel attributes can be long-typed) is exported, with successful spans sampled at a configurable rate. [ADR-0032](doc/adr/ADR-0032-opentelemetry-tracing-and-unified-correlation.md).
- The gateway explicitly injects a W3C `traceparent` header into every proxied request — Spring Cloud Gateway's routing filter does not do this automatically the way a `WebClient`-based call would. This was found broken (each hop rooting its own independent trace) and fixed this session, verified live via a real checkout producing a backend span with a genuine external parent span ID.
- A `CAN_TRACE`-gated Settings toggle lets an authorized user force full-detail tracing for their own session (`X-Force-Trace` header → span attribute), independent of the ambient sampling rate.

### Correlation — one ID, front-to-back, across an asynchronous saga
A single correlation ID is generated at the true system entry point (the gateway or the browser), propagated through every synchronous HTTP hop via header, **and** carried through the outbox pattern as a Kafka message header — captured into the outbox row in the same DB transaction as the business write, then re-attached when the poller actually publishes. Verified live: the identical correlation ID appears in `order-service`'s `ORDER_CREATED` and `payment-service`'s `PAYMENT_SUCCESS` log lines, across the Kafka boundary.

### Metrics & alerting — not just dashboards
- Prometheus scrapes every service's `/actuator/prometheus`; Grafana dashboards include an orders-per-minute panel (`orders_total` counter) with explicit documentation of Prometheus's 6h retention limiting historical (day/week/month/year) analysis — a real, named limitation rather than an implied "and it does everything."
- **Alerting closes a real gap found this session**: dashboards previously existed with nothing pushing anywhere — a genuinely pull-based, must-be-watched-manually setup. A `ServiceDown` Prometheus rule now routes through Alertmanager to a small webhook sidecar that appends to a file on a PersistentVolume, readable via `curl` (no `kubectl exec` dependency). Verified end-to-end live: scaled a service to zero, watched the alert fire and land in the file.

### Log aggregation
Loki + Grafana Alloy ship structured logs (`correlationId`, `traceId`, `orderId` all present as parseable log-line fields) from every pod.

## Architectural Decisions Worth Reading First

A reviewer with limited time should read these five, in order — they carry the most architectural weight and are the ones most likely to be asked about in a design review:

1. **[ADR-0002](doc/adr/ADR-0002-zero-trust-spire-app-level.md) — Zero trust without a service mesh.** Two rounds of researched reversal are preserved in the document, not hidden — the final call (plain SPIRE, app-level mTLS) is defended with conflicting benchmark data cited honestly rather than cherry-picked.
2. **[ADR-0007](doc/adr/ADR-0007-saga-outbox-idempotency.md) — Choreography Saga + Transactional Outbox + idempotent consumers.** The mechanism that makes distributed order/inventory/payment consistency work without a distributed transaction coordinator.
3. **[ADR-0035](doc/adr/ADR-0035-cap-pacelc-consistency-model.md) — CAP/PACELC positioning: PA/EL.** Names, explicitly, what ADR-0007 already implements: under a partition between services, this system chooses **Availability** over strict cross-service Consistency (the caller stays responsive via the outbox/circuit-breaker rather than blocking); with no partition, it chooses **Low Latency** over synchronous cross-service confirmation. Within a single service's own Postgres schema, consistency remains fully ACID — this trade-off is specifically about the boundary *between* services, not within one.
4. **[ADR-0006](doc/adr/ADR-0006-k8s-zero-trust-layers.md) — NetworkPolicy and SPIFFE mTLS as independent layers**, satisfying a literal "no service trusts a caller based on network location alone" requirement with two controls that must both fail for a breach, not one control doing double duty.
5. **[ADR-0022](doc/adr/ADR-0022-postgres-statefulset-not-operator.md) + [ADR-0034](doc/adr/ADR-0034-backup-and-replication-posture.md) — deliberate no-HA, no-multi-replica posture, with a named backup story.** Plain `StatefulSet`+PVC, not an HA operator, because a 20-active-user internal system doesn't yet need automated failover — but backup (`pg_dump`, 14-day retention) is real and implemented, not just planned, and the trade-off (RPO ≈ 24h, RTO ≈ untested-but-manual) is stated explicitly rather than left implicit.

## Code Quality

**Being direct about the current state, not the aspirational one**: `doc/architecture/10-development-testing-deployment.md` describes a target testing pyramid (unit, integration via Testcontainers, contract, security, resilience) — an architecture review this session found that pyramid is **not yet delivered**. Every backend service had exactly one test file, the Spring Boot default `contextLoads()` stub with no real assertions; `api-gateway` had **zero** test files (its `spring-boot-starter-test` dependency had been commented out with no explanatory note). `common-lib` was the one exception, with 2 genuine unit tests.

This session closed part of that gap rather than leaving it stated-but-unfixed:
- Re-enabled `api-gateway`'s test dependency and added its missing stub test — doing so immediately surfaced two real, separate configuration bugs (a missing property default, and OAuth2 client registration having no fallback outside a live config-server), both fixed. Running the full reactor's tests for the first time this session also surfaced `payment-service`'s pre-existing (not caused by this session) stub-test failure — a nested-placeholder default that Spring Boot's newer `PlaceholderParser` couldn't resolve outside a live config-server — fixed with flat inline defaults.
- Added real regression tests for every non-trivial fix made this session: `AuditLogger`'s key-name redaction (3 tests), `ErrorAlwaysSampledSpanExporter`'s dual string/long status-attribute handling (5 tests), and `DuplicateEmailException`'s no-longer-leaks-PII behavior (1 test) — 9 new tests total, all passing.
- The remaining gap (real unit/integration coverage for business logic across the other 6 services) is a named, honest follow-up, not something this README pretends is solved.

**What *is* real and enforced**: the CI pipeline blocks a merge on `mvn verify` failure, CodeQL/Dependency-Check/gitleaks findings, and (as of this session) Trivy HIGH/CRITICAL image findings — code quality here currently means "the code that exists is scanned hard," not "the code that exists is thoroughly unit-tested." Both matter; only the first is currently strong.

## Compliance Posture

**No compliance certification is claimed anywhere in this repository.** [ADR-0037](doc/adr/ADR-0037-soc2-trust-services-criteria-mapping.md) is an honest, evidence-based mapping of current controls against SOC 2's Trust Services Criteria — it names real gaps (no RTO/RPO, no data-subject-rights process at the time of writing, edge TLS not implemented locally) exactly as clearly as it names what's real (RBAC, mTLS, audit logging, sealed secrets, CI security gates). [ADR-0039](doc/adr/ADR-0039-soc2-closure-roadmap.md) researched actual 2026 platform pricing (Vanta/Drata/Secureframe/Sprinto, $30K–$120K for a first Type II audit) and concluded, correctly for this system's current scale, not to buy one yet — there is no external customer commitment that would make that spend justified today.

Data classification ([ADR-0040](doc/adr/ADR-0040-data-classification-and-retention.md)) and edge TLS options for a real production deployment ([ADR-0042](doc/adr/ADR-0042-edge-tls-production-options.md)) are both documented with the same honesty: what's real, what's deferred, and exactly why.

## Capacity & Scale

[ADR-0036](doc/adr/ADR-0036-capacity-planning-back-of-envelope.md) works the actual numbers for this system's stated target — 100 users, ~20 concurrently active, ~10,000 products today, projected 5 years forward — against the real provisioned resource requests/limits, not hand-waved. Conclusion, stated plainly: compute and storage have roughly an order of magnitude of headroom at this scale; the system's real constraint is **availability** (single replica everywhere), not throughput. [ADR-0041](doc/adr/ADR-0041-load-testing-deferred-and-base-strategy.md) makes the corresponding honest call not to run a load test right now, for a stated reason (small fixed user base, wide estimated margin, availability is the binding constraint) — with a concrete, evidence-backed strategy (k6, specific target scenarios) ready to execute the moment a real trigger condition (an SLA, a non-internal launch) makes load testing worth the time.

## Full Documentation Index

- **[doc/architecture/README.md](doc/architecture/README.md)** — TOGAF ADM-aligned architecture documentation (14 documents, Preliminary through Phase H)
- **[doc/adr/](doc/adr/)** — 42 Architecture Decision Records, one per non-trivial technology or design choice, each with Context / Options Considered / Evidence / Decision / Consequences
- **[doc/architecture/14-roles-and-permissions.md](doc/architecture/14-roles-and-permissions.md)** — the full RBAC role reference
