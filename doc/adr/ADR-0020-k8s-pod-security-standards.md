# ADR-0020: Kubernetes workload security — Pod Security Standards "restricted" profile

**Status**: Accepted
**Date**: 2026-08-13
**Deciders**: Solution/Security Architect

## Context

ADR-0006 covers network-layer (NetworkPolicy) and identity-layer (SPIFFE mTLS) zero trust. Neither constrains what a compromised container is *allowed to do on the node it runs on* (privilege escalation, host filesystem access, running as root) — a distinct, well-established Kubernetes hardening layer this design hadn't yet addressed.

## Decision

The `ecom` namespace enforces the Kubernetes **Pod Security Standards "restricted" profile** (the strictest built-in K8s standard, applied via the namespace's `pod-security.kubernetes.io/enforce: restricted` label — no extra component, native to Kubernetes 1.25+). Concretely, every Deployment: runs as non-root (already required by the Phase 5 DoD's Docker convention — this makes it enforced, not just a Dockerfile convention that could silently regress), sets `allowPrivilegeEscalation: false`, drops all Linux capabilities by default, uses a read-only root filesystem where the service doesn't need to write locally, and declares resource requests/limits (also closing a resource-exhaustion/noisy-neighbor gap).

## Consequences

- Positive: zero new infrastructure — this is a native K8s admission control, just a namespace label plus per-Deployment `securityContext` fields; closes a real class of container-escape/privilege-escalation risk that NetworkPolicy and mTLS don't address.
- Negative / accepted trade-off: some third-party images (Kafka, RabbitMQ, Postgres, Keycloak) may need explicit `securityContext` tuning to run under "restricted" — a real but bounded, well-documented integration cost per component when Phase 6 stands them up.
- Follow-up required: apply the namespace label and per-Deployment `securityContext` blocks as part of Phase 6's manifests; verify each third-party component (Postgres, Kafka, RabbitMQ, Keycloak) actually starts under the restricted profile, adjusting only where genuinely necessary.

## Related

- ADR-0006 (NetworkPolicy + SPIFFE mTLS — this is the third independent zero-trust layer)
- `doc/architecture/07-migration-planning.md`, Phase 6
