# 10 — Development, Testing & Deployment Lifecycle

(Supplementary to the TOGAF ADM phases — captures the full SDLC path from a developer's machine to a running Kubernetes deployment, since this is a concrete operational concern the numbered ADM phases don't fully address on their own.)

## Local Development

- Each service is independently runnable via Maven (`mvn spring-boot:run`) against `application-local.yml` profiles pointing at local/dev instances of Postgres, Kafka, RabbitMQ, Keycloak (or Testcontainers-managed equivalents — see Testing below).
- `common-lib` changes (including the `spiffe-mtls` module, ADR-0002) are built and installed to the local Maven repo (`mvn install`) before dependent services pick them up — same as any multi-module Maven project; no special tooling needed at this project's scale.
- Config Server serves shared config from `config-repo`; local overrides live in each service's own `application-local.yml`, never committed with real secrets.

## Testing Strategy — Backend and Architecture (scope decision: UI testing excluded, see below)

The testing pyramid applies to **backend services and cross-cutting architecture only**. Per explicit scope decision, automated UI testing (end-to-end browser tests, UI component tests) is not part of this plan — the UI phase (Phase 8) is verified through manual functional walkthroughs of the interface, not an automated suite. This keeps the testing investment focused where the architectural risk actually is (business logic, data integrity, security boundaries, eventing correctness).

| Layer | Tool | What it covers | Phase introduced |
|---|---|---|---|
| Unit tests | JUnit 5 + Mockito | Service-layer business logic, mapper/validator logic, in isolation from infrastructure | Phase 2 |
| Integration tests | Spring Boot Test + Testcontainers (Postgres, Kafka, RabbitMQ) | Real database round-trips, real broker produce/consume, replacing the current no-assertion `contextLoads()` stubs | Phase 2 (Postgres), Phase 3 (Kafka/RabbitMQ) |
| Contract/API tests | REST-assured or MockMvc against real running services | Verifies documented API contracts (Notes.md endpoint list) actually behave as specified, not just compile | Phase 2 onward, per service as implemented |
| Security tests | Spring Security Test + manual token manipulation | Rejected-token cases from Phase 4's DoD (expired/wrong-issuer/tampered JWT), NetworkPolicy/mTLS enforcement from Phase 6's DoD | Phase 4, Phase 6 |
| Resilience tests | Manual fault injection (kill a dependency mid-test) + Resilience4j actuator metrics | Circuit breaker/retry behavior actually observed under failure, not just configured | Phase 2 |
| UI verification | **Manual functional walkthrough only** — no automated suite | Login → browse → checkout journey works from a real browser | Phase 8 |

**Coverage expectation**: every DoD checklist item in `doc/architecture/07-migration-planning.md` that says "verified"/"observed"/"tested" maps to one of the rows above (except the UI row, which maps to a manual walkthrough by design).

## CI/CD Pipeline (target — introduced once Phase 1's build is green)

```
Commit/PR
   |
   v
Build          mvn clean install (all 10 modules)
   |
   v
Unit tests      mvn test  (fails the pipeline on any red test)
   |
   v
Integration     mvn verify  (Testcontainers-backed; Postgres/Kafka/RabbitMQ
tests           spun up ephemeral per run, torn down after)
   |
   v
Container build Multi-stage Docker build per changed service (Phase 5)
   |
   v
Compose smoke   docker compose up against the built images, automated
test            curl-based smoke test of the checkout flow (Phase 5+)
   |
   v
Manifest        kubectl apply --dry-run / kustomize build validation for
validation      the ecom namespace manifests (Phase 6+)
```

No stage is skipped to "unblock" a merge — a red unit or integration test blocks the pipeline, consistent with the Definition-of-Done discipline in `doc/architecture/08-implementation-governance.md`.

## Deployment Promotion

1. **Local** — individual services via Maven, for tight dev loops.
2. **Docker Compose** — full-stack integration environment, the first place the *entire* system runs together (Phase 5).
3. **Kubernetes (`ecom` namespace)** — production-representative environment with NetworkPolicy + SPIFFE mTLS enforcement live (Phase 6).

Compose and Kubernetes are kept structurally parallel (same service names, same Spring-profile-driven config) specifically so that a flow verified working in Compose is a reliable signal for Kubernetes behavior — see `doc/architecture/05-technology-architecture.md`, "Environment Parity."

## Rollback

Each Kubernetes Deployment is versioned by image tag; a bad rollout is reverted via `kubectl rollout undo`, not a fresh redeploy from scratch. Database migrations (Flyway, ADR-0004) are additive/backward-compatible within a phase to keep rollback safe — a destructive migration is never shipped in the same release as the code that depends on it.

## Related

- `doc/architecture/07-migration-planning.md` — the phases this lifecycle supports
- `doc/architecture/08-implementation-governance.md` — the DoD discipline this testing strategy exists to satisfy
