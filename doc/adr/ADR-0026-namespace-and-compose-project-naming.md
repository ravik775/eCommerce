# ADR-0026: Kubernetes namespace `ecom` and Docker Compose project `ecomd`

**Status**: Accepted
**Date**: 2026-08-13
**Deciders**: Solution/Security Architect

## Context

Every prior document (ADR-0001, ADR-0004, ADR-0006, ADR-0020, ADR-0025, and the architecture docs) referred to the Kubernetes namespace as `eCom` (mixed case), matching requirement #3's literal casing. Caught mid-implementation: Kubernetes namespace names must be valid RFC 1123 DNS labels — lowercase alphanumeric and `-` only. `eCom` would have failed `kubectl create namespace` validation the first time Phase 6 actually tried to apply it. This wasn't a style preference to reconsider; it was a latent bug that hadn't been exercised yet because Phase 6 doesn't exist.

## Decision

- **Kubernetes namespace: `ecom`** (lowercase), corrected across every ADR and architecture doc that referenced `eCom`. This is a naming correction, not a design reversal — nothing about the zero-trust/NetworkPolicy/Pod Security decisions in ADR-0006/ADR-0020 changes, only the literal string.
- **Docker Compose project name: `ecomd`**, for the `docker-compose.yml` built in Phase 5 (`doc/architecture/07-migration-planning.md`) — sets the Compose project name (`docker compose -p ecomd` / `COMPOSE_PROJECT_NAME=ecomd`), which also becomes the default network/container name prefix. Chosen to be visually distinct from the K8s `ecom` namespace in logs/tooling output, so it's always obvious which environment a given container or resource belongs to.

## Consequences

- Positive: avoids a real deployment-time failure that would only have surfaced when Phase 6 actually ran `kubectl apply`; Compose and K8s environments are now trivially distinguishable by name.
- Negative / accepted trade-off: none — this is a pure correction with no functional trade-off.
- Follow-up required: none outstanding — the correction is already applied everywhere `eCom` was referenced.

## Related

- ADR-0006 (K8s zero trust — namespace `ecom`), ADR-0020 (Pod Security Standards — namespace `ecom`), ADR-0021 (Kustomize manifests under `ecom`)
- `doc/architecture/07-migration-planning.md`, Phases 5–6
