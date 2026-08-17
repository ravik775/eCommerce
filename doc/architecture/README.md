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
| [ADR-0030](../adr/ADR-0030-provider-role-and-product-listing-gate.md) | Provider role + DRAFT/LISTED gate for self-service product listings — amends ADR-0025/ADR-0028 |
| [ADR-0031](../adr/ADR-0031-order-history-view.md) | Order History view — amends ADR-0012's three-view UI scope |
| [ADR-0032](../adr/ADR-0032-opentelemetry-tracing-and-unified-correlation.md) | OpenTelemetry distributed tracing, unified correlation ID, force-trace flag, order-rate metrics |
| [ADR-0033](../adr/ADR-0033-admin-role-restricted-to-iam-operations-admin-split.md) | Split ADMIN into IAM-only IAM_ADMIN + operational CATALOG_ADMIN/INVENTORY_ADMIN — amends ADR-0025/0028/0030 |
| [ADR-0034](../adr/ADR-0034-backup-and-replication-posture.md) | Backup/replication posture across Postgres, Kafka, Redis — closes ADR-0022's unimplemented `pg_dump` follow-up |
| [ADR-0035](../adr/ADR-0035-cap-pacelc-consistency-model.md) | CAP/PACELC positioning — names the saga/outbox pattern's existing eventual-consistency trade-off explicitly (PA/EL) |
| [ADR-0036](../adr/ADR-0036-capacity-planning-back-of-envelope.md) | Capacity planning — back-of-envelope sizing for 100 users/20 active/10k products, 5-year horizon |
| [ADR-0037](../adr/ADR-0037-soc2-trust-services-criteria-mapping.md) | SOC 2 Trust Services Criteria — honest current-state mapping, not a compliance claim |
| [ADR-0038](../adr/ADR-0038-local-sast-dast-reproducibility.md) | Local SAST/DAST reproducibility — `scripts/run-sast-dast-local.sh` |
| [ADR-0039](../adr/ADR-0039-soc2-closure-roadmap.md) | SOC 2 closure roadmap — 2026 platform pricing researched, decided not to buy yet |
| [ADR-0040](../adr/ADR-0040-data-classification-and-retention.md) | Data classification and retention policy |
| [ADR-0041](../adr/ADR-0041-load-testing-deferred-and-base-strategy.md) | Load testing — deferred now (small fixed user base), k6-based base strategy defined for when it's needed |
| [ADR-0042](../adr/ADR-0042-edge-tls-production-options.md) | Edge TLS — production options (cert-manager+ACME), local feasibility checked and deferred |
| [ADR-0043](../adr/ADR-0043-otel-tracing-correctness-fixes.md) | OpenTelemetry tracing correctness — 3 filter-ordering/attribute-typing bugs fixed, with regression tests guarding each |
| [ADR-0044](../adr/ADR-0044-gateway-rp-initiated-logout.md) | Gateway RP-initiated logout — hand-written handler closing a stale-Keycloak-SSO-session bug |
| [ADR-0045](../adr/ADR-0045-file-based-service-down-alerting.md) | File-based ServiceDown alerting via Alertmanager — closes the "dashboards but nothing pages anyone" gap |
| [ADR-0046](../adr/ADR-0046-gateway-oauth2-login-failure-handling.md) | Gateway OAuth2 login failure handling — distinguishes "already logged in" from a real credential failure |
| [ADR-0047](../adr/ADR-0047-google-jit-password-login-gating.md) | Password-login gating for Google-JIT users — designed, then reversed for simplicity once Keycloak's own native enforcement was confirmed; both stages recorded |
| [ADR-0048](../adr/ADR-0048-force-trace-server-side-authorization.md) | Server-side authorization for the CAN_TRACE force-trace header — closes a real gap where the client-side UI gate was never actually enforced; amended to add audit logging of denied attempts |
| [ADR-0049](../adr/ADR-0049-gateway-session-absolute-max-age.md) | Gateway session absolute max-age — forces silent re-login every 30 minutes so a Keycloak role grant/revocation can't lag an already-logged-in session indefinitely; amended after a production incident found in the same session (a reactive Mono composition bug that double-invoked chain.filter on every route) |
| [ADR-0050](../adr/ADR-0050-user-service-role-sync-on-every-login.md) | user-service now syncs a returning user's roles from the JWT on every login instead of only at first-ever creation — closes a real gap where a Keycloak role change never reached GET /user/me |
| [ADR-0051](../adr/ADR-0051-spiffe-svid-rotation-hardening.md) | SPIFFE SVID rotation hardening — event-driven WorkloadApiClient watcher (replacing polling) with a 2-min reconciliation safety net; amended twice more for a Retry/CircuitBreaker interaction crash and a missing orderCircuit TimeLimiter that caused false-failure checkout 503s and duplicate orders |
| [ADR-0052](../adr/ADR-0052-gateway-generated-trace-id-end-to-end-propagation.md) | X-Trace-Id is now always gateway-generated (a client-supplied value is never honored, unlike X-Correlation-Id) and propagates across the entire async saga via the same outbox/Kafka-header mechanism correlationId already uses — not just the synchronous HTTP hops |
| [ADR-0053](../adr/ADR-0053-span-attribute-enrichment.md) | New SpanAttributeEnrichmentFilter stamps correlationId/orderId/appTraceId/force_trace onto exported OTel spans — closes a gap where a Tempo trace showed none of these even though all four were present in the corresponding Loki log lines for the same request |
| [ADR-0054](../adr/ADR-0054-notification-rabbitmq-hop-correlation-propagation.md) | notification-service now carries correlation context across its Kafka-consumer-thread -> RabbitMQ-listener-thread hop as message headers, restored via OrderCorrelationScope — closes the one saga hop still cold after ADR-0052/0053 (MDC is thread-local, the dispatch worker runs on a different thread) |
| [ADR-0055](../adr/ADR-0055-gateway-span-context-reliability.md) | Gateway's X-Trace-Id generation deferred to Mono subscription time (was reading Span.current() during eager filter-chain assembly, a documented Spring Cloud Gateway + Micrometer Tracing context-propagation gap) — fixes non-deterministic mismatch between the returned X-Trace-Id and Tempo's real trace ID; fallback path now audit-logged instead of silent |
| [ADR-0056](../adr/ADR-0056-span-attribute-coverage-gap.md) | ADR-0053 was declared done after checking only order-service/one request — Tempo's own tag index showed correlationId/orderId/appTraceId were actually absent for inventory-service, payment-service, notification-service, and the gateway (Servlet-Filter-only mechanism can't reach Kafka/RabbitMQ listener threads or WebFlux). Fixed at the real shared choke point, OrderCorrelationScope, plus the gateway's own span directly |
| [ADR-0057](../adr/ADR-0057-kafka-rabbitmq-observation-enabled.md) | Kafka/RabbitMQ listener and producer spans didn't exist at all (observation instrumentation is opt-in, was never enabled) — ADR-0056's attribute-stamping was always correct, it just had no real span to attach to. Enabled via config for Kafka (auto-configured beans); via explicit setObservationEnabled(true) for notification-service's manually-built RabbitTemplate/listener factory. Live-confirmed: inventory-service and payment-service now show real Tempo spans |
| [ADR-0060](../adr/ADR-0060-spiffe-ca-expiry-warnings-investigated.md) | Investigated "expired CA" WARN log spam found live in inventory/payment-service - confirmed benign (SPIRE's normal trust-bundle rotation overlap, superseded CAs kept for graceful transition, Tomcat explicitly logs them as still-accepted). No code change; live saga completions throughout the warning window confirm no functional mTLS impact |
| [ADR-0059](../adr/ADR-0059-inventory-service-silent-success-logging.md) | InventorySagaConsumer had zero log output on its success paths (reservation, compensating release) — only the failure branch ever logged. Added AuditLogger calls (INVENTORY_RESERVED, INVENTORY_RELEASED) matching the convention every other saga consumer already uses. Closes the gap ADR-0054 deferred |
| [ADR-0058](../adr/ADR-0058-resource-limit-and-startup-optimization.md) | ADR-0057's redeploy took 12+ min with restarts — docker stats showed all 4 services pinned at their 500m CPU limit throughout. Raised CPU limits to 1000m + widened liveness-probe timing (both proven safe). Two corrections needed live: raising limits without checking the namespace ResourceQuota broke scheduling entirely (quota raised 8->10 cores); spring.main.lazy-initialization silently broke the whole Kafka saga (@KafkaListener beans never start under lazy init) and was reverted |

ADR-0007–0009 were raised by the architect self-review ([11-architect-review.md](11-architect-review.md)) rather than the original planning pass. ADR-0010–0012 were added when implementation began, to cover the CI/CD pipeline and UI stack requested at that point. ADR-0013–0022 close out every remaining design gap and open decision found during a "rate this to a 10/10" hardening pass. ADR-0030–0033 came from later feature work (provider self-service listings, order history, OpenTelemetry tracing, the IAM_ADMIN/PLATFORM_ADMIN role split). ADR-0034–0042 came from a 2026-08-16 architecture review evaluating the system against security, observability, compliance, and capacity-planning criteria — each closes either a documentation gap (a decision that was never written down) or an implementation gap (a decision that was written down but never actually built, e.g. ADR-0013's WAF and ADR-0019's Trivy scanning, both closed that same session). ADR-0043–0045 close out the same review's remaining loose ends: real implementation bugs (tracing, gateway logout) that had been fixed in code but never captured as a decision record with a regression guard, plus one new decision (ServiceDown alerting) the review identified as missing entirely. ADR-0046–0048 came from live user-reported issues in a following session (OAuth login failures, Keycloak's real credential-validation mechanism, and a genuine server-side authorization gap in the CAN_TRACE force-trace path) — each investigated with live evidence (Keycloak's own event log and source code, a real Tempo trace search) before being fixed, per this project's standing "fix, regression test, ADR, timestamped to the minute" discipline. Each wave is kept visible rather than backdated into earlier ADRs.

New decisions follow [`../adr/template.md`](../adr/template.md).
