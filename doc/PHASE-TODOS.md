# Phase resumption prompts

One entry per phase with unfinished Definition-of-Done items, in `doc/architecture/07-migration-planning.md` order. Each entry is a self-contained prompt — paste it as-is to resume that phase's work in a fresh session. Every prompt inherits the same standing constraints (below); they're not repeated in each entry.

## Standing constraints (apply to every phase below)

- Research any non-trivial technology/design choice with 2026-dated sources; cite evidence, don't assert from memory.
- Where a real trade-off or conflict exists, present options via a clarifying question — don't decide unilaterally.
- Record every non-trivial decision as an ADR (`doc/adr/`, use `doc/adr/template.md`): Context, Options, Evidence, Decision, Consequences.
- Prefer simplicity, maintainability, reuse. Add infrastructure only when a concrete need forces it — don't build for hypothetical future requirements (see ADR-0027, ADR-0028, ADR-0029 for the pattern: research the option, defer/reject if nothing concrete needs it yet, record why).
- "Done" means genuinely verified live (curl/actual running processes/actual containers), not just code written or unit-tested. Report honestly what was and wasn't verified — an unchecked DoD box is fine; a falsely-checked one isn't.
- Keep `doc/architecture/07-migration-planning.md`, the ADR index (`doc/architecture/README.md`), and the master plan file in sync after every phase — update DoD checkboxes to reflect what was actually verified.
- Use `scripts/dev-up.sh` / `scripts/dev-down.sh` (ADR-0029) to bring the local environment up/down rather than ad-hoc commands; update those scripts if a phase changes what needs to run.

---

## Phase 2 (residual) — Order-service verification gap

Task #5 in this session's tracker is still open even though Phase 2's DoD is otherwise checked.

**Prompt:**
> Verify order-service's place-order → reserve-inventory → charge-payment → status-update flow end-to-end via a real HTTP call against the live environment (`scripts/dev-up.sh`). Confirm Flyway migrations run clean against a fresh Postgres instance. Report exactly which DoD boxes in Phase 2 of `doc/architecture/07-migration-planning.md` are now genuinely verified vs. still assumed, and update the checkboxes accordingly.

## Phase 2 — Local SAST/DAST verification

**Prompt:**
> Phase 1's DoD claims CodeQL SAST + OWASP Dependency-Check SCA run in CI, but local verification (task #11) was never completed. CodeQL CLI isn't available locally — research and pick a local substitute (e.g. SpotBugs/PMD) with 2026-dated evidence it's a reasonable stand-in, run it against the full reactor, and report actual findings (not just "it ran"). SCA: run OWASP Dependency-Check locally and report real findings. DAST: Docker is now available — run ZAP against the live running stack (`scripts/dev-up.sh`) and report real findings, not a config-only check. Record any tooling decision as an ADR only if there's a genuine trade-off to record; otherwise just report results.

## Phase 3 — Remaining live-verification gaps

Phase 3 is "core paths verified live" but 3 DoD boxes remain unchecked.

**Prompt:**
> Against the live environment (`scripts/dev-up.sh`), force an explicit Kafka event redelivery to a consumer and confirm the `ProcessedEvent` idempotency mechanism prevents double-application — don't just read the code, trigger it. Kill a service between its DB commit and outbox publish (mid-transaction), restart it, and confirm the event still publishes exactly once. Write Testcontainers-backed integration tests for the outbox/consumer/compensation paths (Kafka + RabbitMQ), since current verification is manual/live rather than an automated regression suite. Update Phase 3's DoD checkboxes in `doc/architecture/07-migration-planning.md` based on what's actually observed.

## Phase 4 — AuthN/Z live verification (mostly done; Google login blocked)

**Prompt:**
> Unauthenticated-rejection and CUSTOMER-read/403 behavior are confirmed live (see `doc/architecture/07-migration-planning.md` Phase 4). Two DoD items remain: (1) a real Google-federated login — blocked, since `keycloak/ecom-realm.json` has no Google identity provider configured and no Google OAuth credentials exist for this project; either provision them or explicitly accept local-user login as the verified substitute and close this item as "not applicable without external credentials," don't silently drop it. (2) Expired/wrong-issuer/tampered-signature JWTs independently tested and rejected — not yet exercised, do this next. Also re-verify full order placement end-to-end through the gateway with a real CUSTOMER session (only catalog reads were re-verified this session; order placement was last verified in Phase 3 before the Eureka/PKCE/role-binding fixes landed).

## Phase 4b — Gateway edge hardening (RBAC done and live-verified; rate-limit bucket separation still open)

**Prompt:**
> The gateway's role-based admin gate is fully verified live: `CUSTOMER` sessions get 403, `ADMIN` sessions get 201, via the real Authorization Code + PKCE login flow (not a bearer-token shortcut) — see ADR-0028's "Live verification" section for the three real bugs found and fixed along the way (PKCE, realm_access claim source, shortcut-arg field binding). What's still open: confirm two different authenticated users behind the same IP no longer share a Redis rate-limit bucket (`userKeyResolver` fix is in place and `X-RateLimit-*` headers are confirmed present, but distinct-bucket separation under load hasn't been forced/observed). Request throttling (tiered, distinct from the flat rate limit) and gateway observability (structured metrics/tracing) remain explicitly out of scope for this phase — they're Phase 7's job; don't build them here.

## Phase 4c — Local dev orchestration scripts (built this session, needs a clean-environment run)

**Prompt:**
> `scripts/dev-up.sh`/`dev-down.sh` were built and spot-verified against an already-running environment (idempotency confirmed) and once against a full app-service restart (all 7 services + gateway + config-server + service-discovery). Still needed: a true clean-state run — stop everything including containers (`dev-down.sh --full`), confirm all containers/processes are gone, then run `dev-up.sh` from nothing and confirm it brings up the entire stack with zero manual intervention. Report any script bugs found (e.g. a service that starts before its real dependency is ready) and fix them directly in the script — don't work around them by hand.

## Phase 5 — Containerization (Docker Compose) — complete

Dockerfiles, `docker-compose.yml`, clean-state/idempotency verification, and the full CUSTOMER/ADMIN OIDC login + RBAC flow are all done and live-verified through the fully containerized stack (see `doc/architecture/07-migration-planning.md` Phase 5 for all 7 real bugs found and fixed along the way — including the Keycloak `KC_HOSTNAME_BACKCHANNEL_DYNAMIC` fix for the browser-vs-container-internal UserInfo call rejection).

**Remaining nice-to-have (not blocking, not a gap in what's verified):**
> Re-run the full order→payment→notification checkout walkthrough (not just catalog login/RBAC) against the containerized stack — it was verified against host-run services in Phase 3/4, and the auth layer that would have blocked it in the container topology is now fixed, so this is confirmatory rather than exploratory. Once done, retire `scripts/dev-up.sh`'s Java-process startup per ADR-0029's follow-up note, keeping only what still applies (e.g. a thin wrapper around `docker compose up` if useful).

## Phase 6a — K8s manifests, service discovery, NetworkPolicy (mostly done; one real cluster-capability gap)

Kustomize base+overlays, all 9 app/infra pods live-verified running under `ecom`, K8s-native Service DNS + ConfigMaps/Secrets replacing Eureka/Config Server (ADR-0008), NetworkPolicy manifests written and applied. Full test log: `doc/k8-security-test.md`.

**Prompt:**
> Phase 6a's manifests and pod deployment are done and live-verified (`doc/architecture/07-migration-planning.md` Phase 6a, `doc/k8-security-test.md`). One DoD item did NOT pass: a NetworkPolicy blocking test against a live pod found the policies aren't enforced at all on this machine's Docker Desktop Kubernetes — there's no NetworkPolicy-capable CNI installed (confirmed: no Calico/Cilium/Flannel/Weave pod in `kube-system`). Either (a) install a NetworkPolicy-enforcing CNI on this cluster (Calico is the standard local choice) and re-run the exact test in `doc/k8-security-test.md` §3 to get a real pass, or (b) if the real deployment target's cluster already has NetworkPolicy support by default (check before assuming), treat this as verified-by-target-environment instead and don't spend more effort replicating it locally. Also decide whether to build the deferred Ingress+WAF (ADR-0013) now — it was never actually in this sub-phase's DoD (only in Phase 6's original description) and browser-facing testing has used `kubectl port-forward` instead, but Phase 8 (UI) will need a real URL eventually.

## Phase 6b — Zero Trust: SPIRE/SPIFFE mTLS (namespace `ecom`)

Not started.

**Prompt:**
> Build SPIRE server/agent + `common-lib`'s `spiffe-mtls` module, gateway multi-replica deployment behind the Redis-backed rate limiter (ADR-0009), and re-enable in-cluster Postgres as a StatefulSet+PVC (ADR-0022) — Phase 6a moved Postgres to a standalone Docker container as a local-dev-only resource-saving deviation (`k8s/base/postgres.yaml` already exists, just re-add it to `k8s/base/kustomization.yaml`'s resource list and point `SPRING_DATASOURCE_URL` back at the in-cluster `postgres` Service). Before writing SPIRE/SPIFFE integration code, re-confirm ADR-0002's app-level-mTLS-via-common-lib decision still holds — a lot can change in the SPIFFE/Java ecosystem; do fresh 2026 research on the `spiffe/java-spiffe` library's current state before committing code to it. Verify live: a plaintext non-mTLS connection attempt between two services is actually rejected (raw `openssl s_client`/curl test, not a config read); SPIRE SVID rotation observed working at least once without a service restart; the gateway running 2+ replicas confirmed to share one Redis-backed rate limit, not one per replica; every service's SPIFFE ID follows the documented naming scheme. `kubectl exec` was broken on this cluster during Phase 6a (see `doc/k8-security-test.md`'s environment note) — check whether it's still broken before assuming it works for whatever live verification this phase needs.

## Phase 7 — Observability, Rate Limiting, Audit (SOC2 alignment)

Not started. Explicitly where Phase 4b's deferred request-throttling and gateway-observability items land.

**Prompt:**
> Wire Prometheus + Grafana + OpenTelemetry tracing across all services (not just the bare actuator/Prometheus dependency api-gateway already has). Design and build request throttling as a distinct concept from the flat rate limit already in place (ADR-0009) — per-client/per-role tiers, reusing the same Redis infra; research 2026-current patterns for tiered rate limiting with Spring Cloud Gateway + Redis before implementing. Build a structured audit log stream for auth events and money-movement actions. Migrate secrets out of plaintext config-repo YAML into K8s Secrets (closes the original hardcoded-credential gap from the initial audit). Verify live: a Grafana dashboard actually shows live per-service metrics; a deliberately-exceeded rate limit actually produces an observed 429; an audit trail is actually queryable for a full login→order→payment sequence with no gaps; a repo-wide secret scan finds no plaintext secret in committed config.

## Phase 8 — Minimal Checkout UI

Not started. Explicitly out of scope for automated UI testing per this phase's own DoD note — manual walkthrough only.

**Prompt:**
> Build a minimal SPA: Keycloak-redirect login (no custom password UI), catalog browse, cart, checkout calling the gateway. Research 2026-current lightweight SPA tooling before picking a stack — this UI is intentionally thin (no business logic client-side), so don't over-select framework complexity for that scope; if a trade-off exists between options, ask rather than deciding unilaterally. Containerize it with the same non-root/health-check conventions as the backend services, and add it to both `docker-compose.yml` and the K8s manifests. Verify manually (automated UI testing is out of scope by design): a real user logs in with Google, browses, and completes checkout end-to-end; a page reload mid-session preserves login state.

---

## Related

- `doc/architecture/07-migration-planning.md` — the authoritative phase list and DoD source; this file only adds resumption prompts, it doesn't replace it as the source of truth for what's checked.
- `doc/architecture/README.md` — ADR index.
