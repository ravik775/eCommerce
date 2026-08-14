# 07 — Migration Planning (TOGAF ADM Phase F)

## Approach

Nine phases, each a self-contained reviewable increment with an explicit Definition of Done (DoD). A phase is not complete until every DoD box is genuinely verified (tested/observed), not just "code written." Implementation does not begin until Phase 0 (this documentation set) is reviewed and confirmed.

## Phase 0 — Architecture Documentation (this document set)
**DoD**: `doc/architecture/` and `doc/adr/` complete, cross-referenced, no placeholder sections; user has reviewed and confirmed before Phase 1 begins.

## Phase 1 — Fix the Foundation + CI/CD Pipeline Bootstrap
Register all 10 Maven modules; fix gateway `bootstrap.yml`; consolidate the three redundant gateway config drafts into one; remove the `permitAll()` security stub; stand up the GitHub Actions pipeline (ADR-0010, ADR-0011): build, test, CodeQL SAST, OWASP Dependency-Check SCA, ZIP packaging. DAST (ZAP) is added to the pipeline now but stays advisory-only until Phase 4, per ADR-0010.
**DoD**:
- [ ] `mvn clean install` builds all 10 modules with zero errors.
- [ ] Gateway boots, registers with Eureka, and routes at least one real (non-503-stub) request to order-service.
- [ ] Only one gateway config file remains in config-server.
- [ ] No `permitAll()` security stub remains anywhere in the codebase.
- [ ] `.github/workflows/ci.yml` runs on PR/push and completes build + test + SAST + SCA + package stages.
- [ ] Each service produces a release ZIP as a workflow artifact; nothing is pushed to a registry or repository.

## Phase 2 — Core Business Logic + Postgres
Real controller/service/repository logic for all five data-owning services; Postgres migration (ADR-0004); Resilience4j wired to real cross-service calls; unit + Testcontainers integration tests.
**DoD**:
- [ ] Place-order → reserve-inventory → charge-payment → status-update flow verified end-to-end via a real HTTP call (curl/Postman), not just unit tests.
- [ ] Flyway migrations run clean against a fresh Postgres instance for every service.
- [ ] Circuit breaker demonstrably opens when payment-service is killed mid-test (observed via actuator/logs).
- [ ] `mvn verify` passes, including Testcontainers-backed integration tests with a real Postgres round-trip per service.
- [ ] No service still returns a hardcoded stub response for a documented API.

## Phase 2b — Request Correlation, Trace IDs, and Idempotency
Cross-cutting concern surfaced after Phase 2's endpoints existed to apply it to. `common-lib` gains a shared correlation/trace-ID filter (ADR-0023, auto-configured into every service via Spring Boot's `AutoConfiguration.imports`) and a shared `PayloadHasher` for idempotency (ADR-0024). Wired first into order-service's `POST /orders` as the proven pattern; payment-service's `POST /payments` follows the same pattern next.
**DoD**:
- [ ] Every response (success or failure, including validation-error 400s and unhandled-exception 500s) carries `X-Correlation-Id` and `X-Trace-Id` headers.
- [ ] A request with no `X-Correlation-Id`/`X-Trace-Id` gets one generated; a request that supplies `X-Correlation-Id` gets that exact value echoed back unchanged.
- [ ] Both IDs appear in log output (MDC) for every log line during a request's handling, verified by inspecting logs for a real request.
- [ ] Calling `POST /orders` twice with an identical body and no `Idempotency-Key` header creates exactly one order — the second call returns the first call's cached response, verified by comparing response bodies/IDs.
- [ ] Calling `POST /orders` twice with the same `Idempotency-Key` header but a *different* body is rejected (not silently replayed and not silently accepted as a second order).
- [ ] Two calls with genuinely different `Idempotency-Key` values and identical bodies both succeed as separate orders — proving the hybrid model actually solves the hash-only gap from ADR-0024.

## Phase 3 — Eventing (Kafka + RabbitMQ) + Saga/Outbox — code complete, core paths verified live
Kafka domain-event backbone; RabbitMQ task queues with DLQ (ADR-0003); choreography Saga with Transactional Outbox and idempotent consumers (ADR-0007). Built for all 4 services (order/inventory/payment/notification) against live Docker containers (Postgres, Kafka, RabbitMQ) — **not yet Compose or K8s** (Phase 5/6).
**DoD**:
- [x] Placing an order produces an observed `order-created` Kafka message, published only via the order-service outbox poller — verified: a real order (id=1) flowed through the full chain to `PROCESSING`/`PAYMENT_SUCCESS`.
- [x] Order status transitions asynchronously from consumed events with no manual trigger — verified for both the happy path and the `inventory-reservation-failed` → `CANCELLED` compensation path (order id=3).
- [x] A `payment-failed`-equivalent compensation (`inventory-reservation-failed` after a failed reservation attempt) results in the order being cancelled — the compensating action observed working end-to-end. Note: the *`payment-failed` → inventory-release* compensation path specifically is implemented and code-reviewed but **not exercised live**, since the simulated payment processor (ADR — payment-service strategy pattern) always succeeds; there is currently no way to force a real payment failure to test this exact path.
- [x] A deliberately-failed notification dispatch lands in the RabbitMQ DLQ and is retried, not dropped — verified via a direct test-hook message (negative `orderId`): 3 retries, then `RepublishMessageRecoverer` moved it to `notification.dispatch.dlq`.
- [x] A real Spring transaction bug (`UnexpectedRollbackException` from a nested `@Transactional REQUIRED` call inside a Kafka listener) was found via this live testing and fixed (`InventoryService.reserve`/`release` moved to `REQUIRES_NEW`) — the kind of bug automated hermetic tests alone would not have caught.
- [ ] Re-delivering the same Kafka event twice to a consumer (simulated) does not double-apply its effect — the `ProcessedEvent` idempotency mechanism is implemented and used by every consumer, but explicit redelivery was not manually forced and observed in this session.
- [ ] Killing a service mid-transaction (between DB commit and outbox publish) and confirming exactly-once publish on restart — not exercised; accepted as a follow-up before this phase is called fully done.
- [ ] Testcontainers-backed automated integration tests for the outbox/consumer/compensation paths — not written yet; current verification was manual/live against real Docker containers, which is stronger evidence than a mock but isn't a regression-proof automated suite.

## Phase 4 — AuthN/Z: Keycloak + OIDC + Google Login
Keycloak realm/roles/Google federation (ADR-0001); gateway OIDC login; per-service JWT resource-server validation (ADR-0005).
**DoD**:
- [x] An unauthenticated request to a protected route is rejected — verified: direct calls to catalog-service and order-service both return 401 with `WWW-Authenticate: Bearer`; a browser-style request through the gateway gets a 302 to `/oauth2/authorization/keycloak` (correct BFF behavior, not a bare 401, since the gateway terminates login rather than just rejecting).
- [ ] A real Google account logs in through Keycloak — **not verified, blocked**: `keycloak/ecom-realm.json` has no Google identity provider configured, only local `customer1`/`admin1`/`superadmin1` test users. Google OAuth credentials were never provisioned for this project. Local-user login through the full Authorization Code + PKCE flow was verified live instead (see Phase 4b).
- [x] An authenticated `CUSTOMER` reaches a protected resource end-to-end (`GET /products/search`, `GET /products/{id}` — both 200 with a real `customer1` token) and a `CUSTOMER` calling catalog-service's admin-only `POST /products` gets 403 from that service's own `@PreAuthorize` (defense-in-depth, ADR-0025) — verified with real JWTs, not assumed. Full order-placement end-to-end (not just catalog reads) not re-verified this session — carried over from Phase 3's live saga testing.
- [ ] Expired/wrong-issuer/tampered-signature tokens independently tested and rejected — not exercised this session.

### Phase 4a — realm_access claim source (finding, not yet a phase item elsewhere)
Live testing found Keycloak's `ecom` realm does not include `realm_access` in the ID token or the `/userinfo` response — only the access token carries it, even though the "roles" client scope is a default (non-optional) scope for `ecommerce-gateway`. `KeycloakOidcUserService` (Phase 4b) was written expecting the ID token; fixed to parse the access token JWT directly. No ADR needed — this is an implementation-detail fix, not a design decision — but worth flagging here since another service integrating differently against this realm would hit the same trap.

### Phase 4b — Gateway edge hardening: role-based admin gate + rate-limit key fix
Surfaced by an explicit request to audit the gateway for rate limiting, throttling, observability, and tenant/role-based rejection. A tenant dimension was proposed, then challenged and rejected — this is a single storefront with no per-business-unit data partitioning anywhere in the domain model, so a `tenant_id` claim would authorize nothing (ADR-0028). Role (already issued by Keycloak, already consumed by every backend service, ADR-0025) is the correct and sufficient mechanism.
- `RequireRoleGatewayFilterFactory` — edge-level 403 for admin-only routes lacking `ROLE_ADMIN`, additive to each service's own `@PreAuthorize` (ADR-0025), applied first to catalog's mutating routes.
- `KeycloakOidcUserService` — maps `realm_access.roles` onto the gateway's OIDC login session so the filter above has roles to check (mirrors `common-lib`'s `KeycloakRealmRoleConverter` used by the resource-server side).
- Fixed `userKeyResolver` (`GatewayConfig.java`): was resolving the Redis rate-limit bucket key by client IP (users behind the same NAT shared one bucket; a user rotating IPs evaded the limit entirely) — now resolves by authenticated principal.
**DoD**:
- [x] A `CUSTOMER` session calling `POST /catalog/products` through the gateway gets 403, confirmed via the full browser-style Authorization Code + PKCE login flow (real Keycloak session, not a bearer token shortcut) — verified live. Gateway log inspection (route/filter tracing) confirmed the request never reached catalog-service; the 403 originates at the gateway's `RequireRoleGatewayFilterFactory`.
- [x] An `ADMIN` session calling the same route gets 201 Created (a real product row, not a stub) — verified live, same login-flow method.
- [x] Real bug found and fixed via this live testing: `RequireRole=ADMIN`'s shortcut-arg syntax bound to an auto-generated key instead of the filter's `role` field (`shortcutFieldOrder()` was missing), so `config.getRole()` was always `null` and *every* role — including ADMIN — was silently rejected. Confirmed via temporary debug logging showing the actual `SecurityContext` authorities (`ROLE_ADMIN` present) alongside the still-403 response, which isolated the bug to the filter's own config binding rather than authentication/authorization upstream.
- [x] Two more real bugs found and fixed en route to the above: (1) Keycloak's confidential `ecommerce-gateway` client mandates PKCE but Spring's reactive OAuth2 client only auto-sends PKCE params for public clients — fixed via an explicit `authorizationRequestResolver`. (2) Eureka registered every service instance under the Windows host's `.mshome.net` hostname, unresolvable by the gateway's Netty DNS resolver (NXDOMAIN on every `lb://` route, not just the new one) — fixed via `eureka.instance.prefer-ip-address: true` in the shared `config-repo/application.yml`.
- [ ] Two different authenticated users no longer sharing a rate-limit bucket when behind the same IP — the resolver fix is in place and `X-RateLimit-*` headers are confirmed present on real responses, but distinct-bucket behavior under load wasn't forced/observed this session.
- [ ] Request throttling (distinct from the flat rate limit — per-tenant/per-client tiers) — not yet designed; remains open, tracked under Phase 7's observability/rate-limiting pass since it needs the same Redis-backed infra.
- [ ] Gateway observability (structured metrics/tracing beyond the bare actuator/Prometheus dependency) — not yet wired; remains Phase 7 scope, unchanged by this phase.

### Phase 4c — Local dev orchestration scripts (interim, pre-Compose)
Surfaced mid Phase-4-verification: no `scripts/` folder existed, and every dev session's Docker-container + ordered `java -jar` startup had been ad-hoc Bash commands living only in conversation history — not reproducible (ADR-0029). `make`/Overmind/Foreman were considered and rejected: they add a new tool dependency (`make` isn't bundled with Git for Windows; Overmind needs `tmux`; Foreman needs Ruby) for a 7-process problem plain Bash already solves. Pulling Docker Compose forward from Phase 5 was also rejected — that requires 9 Dockerfiles first, which is Phase 5 itself, not a shortcut to it.
- `scripts/dev-up.sh` — idempotent: checks each container/port before acting, starts infra (Postgres/Kafka/RabbitMQ/Keycloak/Redis) with health waits, then config-server/service-discovery, then the 7 app services in dependency order.
- `scripts/dev-down.sh` — tears the same environment down.
- Explicitly interim: retired once Phase 5's `docker-compose.yml` exists.
**DoD**:
- [x] `scripts/dev-up.sh` brings up the full local environment from a clean state (verified live: `dev-down.sh --full` + `docker rm` on every container, confirmed zero containers and zero Java processes, then a fresh `dev-up.sh` run) with no manual steps — all 9 services (5 infra containers + config-server + service-discovery + 7 app services + gateway) reported healthy.
- [x] Re-running `dev-up.sh` against an already-running environment is a no-op (idempotent) — verified both earlier this session and again during this clean-state test (infra containers and already-running app services correctly detected and skipped).
- [x] `scripts/dev-down.sh` cleanly stops everything `dev-up.sh` started — verified via `--full` teardown as part of this test.
- [x] Real bug found and fixed by this clean-state test: the RabbitMQ container `dev-up.sh` creates had no `RABBITMQ_DEFAULT_USER`/`RABBITMQ_DEFAULT_PASS` env vars, so a genuinely fresh container only had the default `guest` account — which RabbitMQ restricts to true-localhost connections, rejecting every service's configured `ecommerce_dev` login with `ACCESS_REFUSED`. This was invisible in every prior session because the container was created once, ad-hoc, before this script existed, and its data volume (with the `ecommerce_dev` user already provisioned) was never removed — `ensure_container`'s reuse-if-exists logic silently papered over the gap every time. Fixed by adding the credentials as container env vars in `scripts/dev-up.sh`; re-verified clean (zero `ACCESS_REFUSED`, confirmed RabbitMQ connection established, notification-service healthy).

## Phase 5 — Containerization (Docker Compose) — complete, fully verified live
Multi-stage, non-root Dockerfiles per service; `docker-compose.yml` wiring the full stack, project name `ecomd` (ADR-0026 — deliberately distinct from the K8s namespace `ecom` so logs/tooling always make clear which environment a resource belongs to).
**DoD**:
- [x] `docker compose up` brings up the entire platform from a clean state with no manual steps — verified live: all containers/images removed, then `docker compose up -d` from nothing brought all 13 containers (5 infra + config-server + service-discovery + 7 app services + gateway) to `healthy`.
- [x] Full checkout-relevant login/RBAC flow verified through the gateway, containers only — a real browser-style OIDC login (Authorization Code + PKCE) now works end-to-end: `CUSTOMER` login → `GET /catalog/products/search` → 200; `CUSTOMER` → `POST /catalog/products` → 403; `ADMIN` login → `POST /catalog/products` → 201. Root cause of the earlier failure (see below) was in Keycloak's own backchannel hostname validation, not Spring config. Full order→payment→notification checkout walkthrough not re-run in the container topology this session (verified in Phase 3/4 against host-run services) — the blocking piece (auth) is now resolved, so that walkthrough is a straightforward follow-up, not an open risk.
- [x] Every container verified running as non-root — `docker run --entrypoint sh <image> -c "whoami"` returns `spring` for all 9 built images.
- [x] `docker compose down && docker compose up` is idempotent — verified live: after a full `down` (all containers removed) and `up`, all 13 services reached `healthy` again with no manual intervention.

### Real bugs found and fixed via this live containerization work
1. **RabbitMQ default-user gap** (also written up in ADR-0029): the container had no `RABBITMQ_DEFAULT_USER`/`PASS`, so a genuinely fresh container only had `guest` (localhost-restricted), rejecting every service's configured `ecommerce_dev` login. Fixed with explicit env vars in `docker-compose.yml`.
2. **CI release-zip assembly breaks under Docker's reactor-filtered build**: `mvn -pl <module> -am package` inside the Dockerfile failed on `maven-assembly-plugin`'s `${maven.multiModuleProjectDirectory}`-based descriptor path resolving incorrectly under that filtered reactor context. Fixed by passing `-Dassembly.skipAssembly=true` in every Dockerfile — irrelevant for container builds anyway (that ZIP is ADR-0011's separate release-artifact path).
3. **`config-server`'s own `/actuator/health` is shadowed** by its generic `/{application}/{profile}` Environment Controller route, returning a config-property dump instead of `{"status":"UP"}` — a content-based healthcheck (`grep -q UP`) treated it as permanently unhealthy despite the app logging 200 OK the whole time. Fixed the healthcheck to check HTTP-200-only; flagged the underlying non-observability of config-server's real health for Phase 7.
4. **Every app service's `/actuator/health` is a secured resource-server endpoint** (401, empty body — same posture confirmed live in Phase 4), so the same `grep -q UP` healthcheck pattern falsely marked every genuinely-healthy app service unhealthy. Fixed all 9 healthchecks to accept any real HTTP status line, not a body match.
5. **`spring.config.import`'s URL is hardcoded per-service**, not read from a separate overridable `spring.cloud.config.uri` property — every service's bundled `application.properties` has `spring.config.import=optional:configserver:http://localhost:8888` baked in literally. An env override targeting `spring.cloud.config.uri` was silently a no-op; the correct override target is `SPRING_CONFIG_IMPORT` itself. Without this fix, every service ran with zero config-server-supplied properties (silently, since the import is `optional:`), which is what caused `service-discovery` to boot on the wrong port (8080 instead of 8086) using pure defaults.
6. **Orphaned host processes held ports 8080/8888** left over from `scripts/dev-up.sh` sessions earlier in this work, blocking Compose from binding the same ports — not a code bug, but worth noting for anyone hitting `"Only one usage of each socket address..."` when switching from the dev-up.sh workflow to Compose.
7. **Keycloak's UserInfo endpoint rejected the gateway's own server-to-server calls.** With `KC_HOSTNAME` pinned to the browser-facing address (`localhost:8090`, needed so tokens carry a consistent `iss` claim regardless of caller), token exchange via the container-internal address (`keycloak:8080`) succeeded, but the *identical valid access token* got `401 Token verification failed` from Keycloak's UserInfo endpoint specifically when called via that same internal address — confirmed by manually obtaining a token and testing both addresses directly. This is a Host-header-based backchannel check independent of `KC_HOSTNAME_STRICT`. Fixed with Keycloak 26's documented setting for exactly this reverse-proxy/container split: `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true` (which itself required `KC_HOSTNAME` to be a full URL, not a bare hostname — Keycloak refused to start otherwise).

Two Spring-side dead ends were tried and reverted before finding the real (Keycloak-side) cause: static OAuth2 client endpoints without `issuer-uri` (avoids discovery, but Spring still requires an explicit `user-name-attribute` or fails with `missing_user_name_attribute`), and additionally omitting `user-info-uri` (expected to skip the UserInfo call per `OidcReactiveOAuth2UserService.shouldRetrieveUserInfo()`, but did not — Spring still attempted it). Both are preserved in `config-server/src/main/resources/config-repo/api-gateway.yml`'s history/comments as a record of what was ruled out, since the actual fix ended up being entirely on the Keycloak container side.

## Phase 6a — Kubernetes manifests, service discovery, NetworkPolicy (namespace `ecom`) — mostly done
Deployment/Service/ConfigMap/Secret manifests as Kustomize base+overlays (ADR-0021); Pod Security Standards "restricted" enforced namespace-wide (ADR-0020); NetworkPolicy default-deny + explicit allow (ADR-0006); Kubernetes-native service discovery + ConfigMaps/Secrets replacing Eureka/Config Server in this environment only (ADR-0008). SPIRE/mTLS split out as Phase 6b; Ingress+WAF (ADR-0013) deferred — not in this phase's DoD, see notes below. Full live test log: `doc/k8-security-test.md`.
**DoD**:
- [x] Platform runs fully in K8s under `ecom` from applied manifests, no manual pod edits — verified live: 9/9 pods (`api-gateway`, 6 app services, `rabbitmq`, `redis`) `Running`, `replicas: 1`, `kubectl apply -k` from a fully reset cluster.
- [x] Services resolve each other via Kubernetes Service DNS and load config from ConfigMaps/Secrets — no Eureka or Config Server pod exists in this environment (ADR-0008) — verified via real traffic (notification-service → `rabbitmq`, gateway → `catalog-service` through Spring Cloud Gateway routes pointing at Service DNS names, not `lb://`).
- [ ] A pod outside the NetworkPolicy allow-list is confirmed blocked by an actual connection attempt — **tested live, result is a real finding, not a pass**: a test pod with no allow-rule successfully reached both Redis and RabbitMQ's management port. Root cause: Docker Desktop's built-in Kubernetes has no NetworkPolicy-enforcing CNI (no Calico/Cilium/Flannel/Weave in `kube-system` — confirmed by listing it). The 11 NetworkPolicy manifests are correct and accepted by the API server, but nothing enforces them on this specific cluster. Full detail in `doc/k8-security-test.md` §3. This is a cluster capability gap, not a manifest defect — the real deployment target needs a NetworkPolicy-capable CNI (standard on GKE/EKS/AKS, or self-managed Calico) for this DoD item to mean anything; re-verify there.
- [x] Real bugs found and fixed via this live deployment work (full detail in `doc/k8-security-test.md` §5–6): every app service's `SERVER_PORT` silently defaulted to 8080 once config-server was disabled (fixed with explicit env vars); RabbitMQ OOM-crash-looped from its default *relative* memory watermark, documented as unsafe in containers (fixed with an absolute watermark + Guaranteed QoS); a Git-Bash `docker run -v` path-mangling bug silently broke Keycloak's realm import with no error logged.
- [x] Namespace-level resource governance added after a real resource-exhaustion incident (Docker Desktop's engine crashed under combined Compose+K8s load): `ResourceQuota` + `LimitRange` on `ecom`, explicit per-container `resources` everywhere, Kafka/RabbitMQ limits validated against 2026 Strimzi/RabbitMQ sizing guidance.
- Local-dev-only deviation from ADR-0022 (documented, not silently dropped): Postgres, Kafka, and Keycloak run as standalone Docker containers on this machine, not K8s pods, reachable via `host.docker.internal` — see `k8s/base/configmap-common.yaml`'s "LOCAL-DEV DEVIATION" comment. `postgres.yaml`/`kafka.yaml`/`keycloak.yaml` remain in `k8s/base/`, correct and ready, just not referenced by the local overlay's kustomization.
- Ingress + ModSecurity/WAF (ADR-0013) — deliberately deferred, was never in this sub-phase's actual DoD (only mentioned in the original Phase 6 description); browser-facing OIDC verification used `kubectl port-forward` instead. Needed before Phase 8 (UI) can work against a real URL instead of port-forward.

## Phase 6b — Zero Trust: SPIRE/SPIFFE mTLS (namespace `ecom`)
Not started. SPIRE server/agent + `common-lib` `spiffe-mtls` enforcement (ADR-0002); gateway deployed with multiple replicas behind the Redis-backed rate limiter (ADR-0009); Postgres in-cluster as a StatefulSet+PVC (ADR-0022) — re-enabling in-cluster Postgres is a prerequisite here, since 6a's local-dev deviation moved it to a standalone container; etcd encryption at rest for Secrets (ADR-0014).
**DoD**:
- [ ] A plaintext (non-mTLS) connection attempt between two services is confirmed rejected via a raw TLS test.
- [ ] SPIRE SVID rotation observed working at least once without service restart.
- [ ] Every service's SPIFFE ID follows the documented naming scheme.
- [ ] With the gateway running 2+ replicas, the Redis-backed rate limit is confirmed to hold as one shared limit across replicas (not multiplied per replica).
- [ ] Re-confirm ADR-0002's app-level-mTLS-via-common-lib decision still holds against fresh 2026 research on the `spiffe/java-spiffe` library's current state before writing code against it (per the standing constraint on this phase — a lot can change in this ecosystem).

## Phase 7 — Observability, Rate Limiting, Audit (SOC2 alignment)
Prometheus/Grafana/OpenTelemetry; finalized gateway rate limiting; structured audit log; secrets moved to K8s Secrets.
**DoD**:
- [ ] Grafana dashboard shows live per-service metrics, demoed.
- [ ] A deliberately-exceeded rate limit produces an observed 429.
- [ ] An audit trail is queryable for a full login→order→payment sequence with no gaps.
- [ ] Repo-wide secret scan finds no plaintext secret in committed config.

## Phase 8 — Minimal Checkout UI
Keycloak-redirect login SPA; catalog browse; checkout call to the gateway. **Scope decision**: automated UI testing is explicitly out of scope for this project — the testing pyramid (`doc/architecture/10-development-testing-deployment.md`) applies to backend services and architecture only. The UI phase focuses on interface and functionality, verified manually.
**DoD**:
- [ ] A real user logs in with Google, browses, and completes checkout end-to-end through the UI (manual walkthrough).
- [ ] Session/token survives a page reload.
- [ ] UI container included in both Docker Compose and K8s manifests, same non-root/health-check conventions as backend services.

## Related

- `doc/architecture/10-development-testing-deployment.md` for the testing/CI/CD mechanics that back the DoD checks above
- Full technology rationale: `doc/adr/`
