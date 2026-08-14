# ADR-0008: Kubernetes-native discovery/config in Phase 6, Eureka + Config Server retained for Compose only

**Status**: Accepted
**Date**: 2026-08-12
**Deciders**: Solution/Security Architect (raised in architect self-review, `doc/architecture/11-architect-review.md`, Conflict #1)

## Context

`doc/architecture/05-technology-architecture.md` originally stated that Docker Compose and Kubernetes should be "kept structurally parallel... divergence... is treated as a defect." Applied literally, that meant carrying Eureka and Spring Cloud Config Server into the Kubernetes deployment even though Kubernetes natively provides equivalent capabilities (Service objects for discovery, ConfigMaps/Secrets for config) — two extra always-on components in production duplicating what the platform already does for free.

## Options Considered

| Option | Pros | Cons |
|---|---|---|
| Keep Eureka + Config Server in both Compose and Kubernetes | True environment parity — identical discovery/config code path everywhere, no environment-specific branches | Ships 2 redundant components into production indefinitely; contradicts the "add infrastructure only when needed" principle once Kubernetes already provides the same capability natively |
| Drop Eureka + Config Server in Kubernetes, use Kubernetes-native Service discovery + ConfigMaps/Secrets (Compose keeps Eureka/Config Server, since Compose has no native equivalent) | Leaner production footprint — 2 fewer always-on components in the environment that matters most; uses the platform's own primitives instead of re-implementing them | A deliberate, documented divergence between Compose and Kubernetes — the two environments are no longer identical, which is a real cost that must be tracked, not hand-waved |

## Evidence

- "Kubernetes already provides a built-in Service Registry, making traditional service discovery tools like Eureka redundant... Eureka and Spring Cloud Config can be considered redundant when running on Kubernetes." ([BitInit — eureka-on-kubernetes](https://github.com/BitInit/eureka-on-kubernetes/blob/master/README_en.md))
- Spring's own `spring-cloud-kubernetes` project exists specifically for this transition: swap the Eureka discovery-client dependency for `spring-cloud-kubernetes-discovery`, and the Config Server client for `spring-cloud-kubernetes-config`, which reads directly from ConfigMaps/Secrets. This is a supported, documented Spring project, not a bespoke workaround. ([Spring Cloud Kubernetes reference](https://cloud.spring.io/spring-cloud-kubernetes/reference/html/), [Spring blog — Spring Cloud Kubernetes features](https://spring.io/blog/2021/10/26/new-features-for-spring-cloud-kubernetes-in-spring-cloud-2021-0-0-m3/))

## Decision

**Docker Compose** keeps Eureka + Config Server (no native equivalent exists in plain Compose). **Kubernetes (Phase 6 onward)** drops both: service-to-service calls resolve via Kubernetes Service DNS, and configuration is sourced from ConfigMaps/Secrets via `spring-cloud-kubernetes-config`. This is a deliberate, explicitly-tracked divergence between the two environments, not an oversight — `05-technology-architecture.md`'s "environment parity" principle is updated to describe *behavioral* parity (the same flows work the same way) rather than *mechanism* parity (identical infrastructure components), since insisting on mechanism-identical infra was itself the thing costing unnecessary complexity in production.

## Consequences

- Positive: 2 fewer always-on infrastructure components in the Kubernetes deployment (the environment that matters most for production realism); uses Kubernetes' own primitives instead of duplicating them.
- Negative / accepted trade-off: services need a profile-specific discovery/config client dependency (Eureka-based for Compose, `spring-cloud-kubernetes`-based for K8s) — a real code-path divergence between environments, which must be exercised and tested in both, not assumed to behave identically because "it's just config."
- Follow-up required: `05-technology-architecture.md`'s deployment topology and `07-migration-planning.md`'s Phase 6 Definition of Done are updated to reflect this split; the CI/CD pipeline (`10-development-testing-deployment.md`) must validate both discovery/config paths, not just one.

## Related

- Related architecture docs: `doc/architecture/05-technology-architecture.md`, `doc/architecture/07-migration-planning.md`
- Resolves Conflict #1 from `doc/architecture/11-architect-review.md`
