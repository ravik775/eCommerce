# 06 — Opportunities & Solutions (TOGAF ADM Phase E)

## Build vs. Buy / Adopt Decisions

| Concern | Decision | Rationale |
|---|---|---|
| Identity Provider | Adopt Keycloak (open source, self-hosted) | Avoids building custom OAuth2/OIDC broker logic; industry-standard, well-documented broker pattern (ADR-0001) |
| Workload identity | Adopt SPIRE (CNCF graduated) | Reference implementation of the SPIFFE standard; not something to hand-roll |
| Zero-trust enforcement mechanism | Build a thin `common-lib` wrapper over the official `spiffe/java-spiffe` library, rather than adopt a full service mesh | The "buy" option (Istio/Linkerd) was evaluated and rejected on evidence (ADR-0002) — the right-sized solution here is a small amount of shared code over an official library, not a new infrastructure platform |
| Messaging | Adopt Kafka + RabbitMQ (both open source, both already named as candidates in Notes.md) | Fitness-for-purpose split, not a build decision (ADR-0003) |
| Resilience patterns | Adopt Resilience4j (already a dependency, currently unused) | Already the right tool, just needs to be wired to real code rather than left as example YAML |
| Observability | Adopt Micrometer/Prometheus/Grafana/OpenTelemetry (CNCF-standard stack) | No reason to build custom metrics/tracing when the ecosystem-standard stack integrates natively with Spring Boot Actuator |
| UI | Build a minimal SPA in-house | Requirement is a thin login/browse/checkout flow, not a general storefront — a proven small framework (e.g., React/Vite) is "adopt," the actual pages are necessarily custom |

## Phasing Rationale

The delivery plan (`07-migration-planning.md`) sequences work so that:
1. The application is *correct* before it is *secure* (Phases 1–3 build real business logic and eventing before auth is layered on) — this avoids debugging business logic through an auth layer that isn't stable yet.
2. It is *secure* before it is *containerized/orchestrated* (Phase 4 auth precedes Phase 5 Docker, Phase 6 K8s) — so containerization inherits a working security model rather than needing rework.
3. Infrastructure (SPIRE, service mesh consideration, K8s) is added only in Phase 6, when Kubernetes actually exists to run it in — consistent with the "add infrastructure only when needed" principle.
4. Observability and the UI come last (Phases 7–8) because they depend on everything underneath already working, and instrumenting/UI-wrapping a broken system produces meaningless signal.

## Related

- `doc/architecture/07-migration-planning.md` for the concrete phase sequence and Definition-of-Done per phase
