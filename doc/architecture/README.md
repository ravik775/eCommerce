# Architecture Documentation Index

TOGAF 10 ADM-aligned documentation for the eCommerce platform modernization. Start with `00-preliminary.md` for principles and scope.

| Doc | TOGAF ADM Phase | Covers |
|---|---|---|
| [00-preliminary.md](00-preliminary.md) | Preliminary | Principles, scope, framework tailoring |
| [01-architecture-vision.md](01-architecture-vision.md) | A | Problem statement, stakeholders, target state |
| [02-business-architecture.md](02-business-architecture.md) | B | Roles, order/payment/inventory business flows |
| [03-data-architecture.md](03-data-architecture.md) | C1 | Postgres schema ownership, cross-service data rules |
| [04-application-architecture.md](04-application-architecture.md) | C2 | Service inventory, API contracts, call graph |
| [05-technology-architecture.md](05-technology-architecture.md) | D | Runtime stack, deployment topology |
| [06-opportunities-solutions.md](06-opportunities-solutions.md) | E | Build vs. adopt decisions, phasing rationale |
| [07-migration-planning.md](07-migration-planning.md) | F | Phased delivery plan with Definition-of-Done checklists |
| [08-implementation-governance.md](08-implementation-governance.md) | G | Review gates, DoD discipline, decision governance |
| [09-architecture-change-management.md](09-architecture-change-management.md) | H | How decisions get revised/superseded |
| [10-development-testing-deployment.md](10-development-testing-deployment.md) | (supplementary) | Full SDLC: local dev, testing pyramid, CI/CD, deployment promotion, rollback |
| [11-architect-review.md](11-architect-review.md) | (governance) | Self-review: completeness gaps, simplicity conflicts, event-driven rigor findings, open decision points |
| [12-ci-cd-pipeline.md](12-ci-cd-pipeline.md) | (supplementary) | GitHub Actions pipeline stages: build, test, SAST (CodeQL), SCA (Dependency-Check), package (zip), DAST (ZAP) — no artifact push |
| [13-ui-architecture.md](13-ui-architecture.md) | (supplementary) | Minimal React/Vite SPA: login, catalog, checkout views, session handling, deployment |

## Decision Log

Every non-trivial technology choice referenced above is backed by an evidence-based ADR in [`../adr/`](../adr/):

| ADR | Decision |
|---|---|
| [ADR-0001](../adr/ADR-0001-idp-keycloak.md) | Identity provider — Keycloak brokering Google OIDC |
| [ADR-0002](../adr/ADR-0002-zero-trust-spire-app-level.md) | Zero-trust identity — SPIRE, application-level mTLS, no service mesh |
| [ADR-0003](../adr/ADR-0003-eventing-kafka-rabbitmq.md) | Eventing — Kafka (domain events) + RabbitMQ (task queues) |
| [ADR-0004](../adr/ADR-0004-datastore-postgres-schema-per-service.md) | Data store — PostgreSQL, schema-per-service |
| [ADR-0005](../adr/ADR-0005-api-gateway-boundary.md) | API Gateway responsibilities and defense-in-depth JWT validation |
| [ADR-0006](../adr/ADR-0006-k8s-zero-trust-layers.md) | Kubernetes zero trust — NetworkPolicy + SPIFFE mTLS as independent layers |
| [ADR-0007](../adr/ADR-0007-saga-outbox-idempotency.md) | Distributed transactions — Choreography Saga + Transactional Outbox + idempotent consumers |
| [ADR-0008](../adr/ADR-0008-k8s-native-discovery-config.md) | Kubernetes-native discovery/config in K8s; Eureka + Config Server retained for Compose only |
| [ADR-0009](../adr/ADR-0009-rate-limiter-redis.md) | Gateway rate limiter — Redis-backed (multi-replica gateway) |
| [ADR-0010](../adr/ADR-0010-cicd-pipeline-sast-dast.md) | CI/CD pipeline — GitHub Actions, CodeQL (SAST), Dependency-Check (SCA), OWASP ZAP (DAST) |
| [ADR-0011](../adr/ADR-0011-artifact-packaging-zip.md) | Artifact packaging — executable JAR in a release ZIP, WAR available as a fallback profile |
| [ADR-0012](../adr/ADR-0012-ui-stack.md) | UI stack — minimal React + Vite SPA |
| [ADR-0013](../adr/ADR-0013-edge-waf-modsecurity.md) | Edge WAF — ModSecurity + OWASP CRS via ingress-nginx, zero new components |
| [ADR-0014](../adr/ADR-0014-secrets-k8s-native.md) | Secrets management — Kubernetes-native Secrets + etcd encryption at rest |
| [ADR-0015](../adr/ADR-0015-kafka-schema-json-documented.md) | Kafka schema governance — JSON validated against checked-in JSON Schema, no registry service |
| [ADR-0016](../adr/ADR-0016-audit-log-tagged-entries.md) | Audit log storage — tagged entries in the centralized log stack |
| [ADR-0017](../adr/ADR-0017-oidc-pkce-public-client.md) | OIDC public client hardening — PKCE mandatory for the SPA |
| [ADR-0018](../adr/ADR-0018-gateway-cors-policy.md) | CORS — explicit origin allow-list at the gateway |
| [ADR-0019](../adr/ADR-0019-container-image-scanning-trivy.md) | Container image scanning — Trivy, extends the CI pipeline |
| [ADR-0020](../adr/ADR-0020-k8s-pod-security-standards.md) | Kubernetes workload security — Pod Security Standards "restricted" |
| [ADR-0021](../adr/ADR-0021-k8s-manifests-kustomize.md) | Kubernetes manifest format — Kustomize |
| [ADR-0022](../adr/ADR-0022-postgres-statefulset-not-operator.md) | Postgres in-cluster — plain StatefulSet+PVC, not an operator |
| [ADR-0023](../adr/ADR-0023-correlation-trace-id.md) | Correlation ID + Trace ID — distinct headers, distinct uniqueness guarantees |
| [ADR-0024](../adr/ADR-0024-idempotency-hybrid-key-hash.md) | Idempotency — hybrid client Idempotency-Key with sanitized-payload-hash fallback |
| [ADR-0025](../adr/ADR-0025-jwt-rbac-method-security.md) | Token format (JWT) + RBAC enforcement (Spring Security method security, Keycloak realm roles) |
| [ADR-0026](../adr/ADR-0026-namespace-and-compose-project-naming.md) | K8s namespace `ecom` (naming correction — `eCom` isn't RFC 1123 valid) + Compose project `ecomd` |
| [ADR-0027](../adr/ADR-0027-payment-gateway-integration-deferred.md) | Real payment gateway integration — Stripe+Razorpay options researched, decision deferred (simulated processors continue) |
| [ADR-0028](../adr/ADR-0028-gateway-role-based-admin-gate.md) | Gateway-level admin gate is role-based only — tenant dimension challenged and rejected (no data partitioning exists to enforce it) |
| [ADR-0029](../adr/ADR-0029-local-dev-orchestration-scripts.md) | Local dev orchestration — plain Bash scripts (interim, superseded by Phase 5's docker-compose) over Make/Overmind/pulling Compose forward |

ADR-0007–0009 were raised by the architect self-review ([11-architect-review.md](11-architect-review.md)) rather than the original planning pass. ADR-0010–0012 were added when implementation began, to cover the CI/CD pipeline and UI stack requested at that point. ADR-0013–0022 close out every remaining design gap and open decision found during a "rate this to a 10/10" hardening pass — no pending decisions remain as of ADR-0022; each wave is kept visible rather than backdated into earlier ADRs.

New decisions follow [`../adr/template.md`](../adr/template.md).
