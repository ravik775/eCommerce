# ADR-0021: Kubernetes manifest format — Kustomize

**Status**: Accepted
**Date**: 2026-08-13
**Deciders**: Solution/Security Architect

## Context

Tracked as an open decision in `doc/architecture/07-migration-planning.md` since Phase 6 was first planned. Manifests need to express per-environment differences (at minimum: local K8s dev vs. whatever comes after) without duplicating full YAML per environment.

## Options Considered

| Option | Fit |
|---|---|
| Raw YAML per environment | Simplest conceptually, but duplicates full manifests per environment with no reuse — every shared change (e.g., adding a NetworkPolicy label) means editing N copies |
| Kustomize | Native to `kubectl` (`kubectl apply -k`, built into the client since 1.14) — no extra tooling to install, no templating language to learn/debug. One `base/` directory per service plus small per-environment `overlays/` patches (replica count, resource limits, image tag) |
| Helm | Most powerful (templating, packaging, releases, rollback via `helm` CLI, a large ecosystem of third-party charts for Postgres/Kafka/RabbitMQ/Keycloak) — but Go-template-in-YAML is a well-known source of hard-to-debug errors, and it's a CLI + chart-authoring convention this project doesn't otherwise need for its own 8 services |

## Decision

**Kustomize.** Each service gets a `k8s/base/` (Deployment, Service, ConfigMap references) plus `k8s/overlays/<env>/` patches for anything environment-specific. Matches this project's consistent "prefer the option with zero new tooling" pattern (ADR-0002, ADR-0013, ADR-0014, ADR-0015 all made the same call). Third-party components (Postgres, Kafka, RabbitMQ, Keycloak) may still use their own well-maintained Helm charts where that's genuinely the standard distribution mechanism for that component — this decision governs manifests for *this project's own 8 services*, not a blanket ban on Helm everywhere.

## Consequences

- Positive: no new CLI/tooling dependency beyond `kubectl` itself; overlay-based environment differences stay small and readable as plain YAML patches, not template logic.
- Negative / accepted trade-off: less powerful than Helm for complex conditional logic or packaging-for-external-distribution — not a real cost here, since these manifests are for this repo's own deployment, not for distribution to third parties.
- Follow-up required: scaffold `k8s/base/` and `k8s/overlays/` structure when Phase 6 begins.

## Related

- `doc/architecture/07-migration-planning.md`, Phase 6 — this closes that phase's "Open Decisions" item
