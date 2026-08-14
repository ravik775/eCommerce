# ADR-0006: Kubernetes zero-trust enforcement — NetworkPolicy and SPIFFE mTLS as independent layers

**Status**: Accepted
**Date**: 2026-08-12
**Deciders**: Solution/Security Architect

## Context

Requirement #3 asks for namespace-scoped ingress/egress rules under `ecom`; requirement #4 asks for SPIFFE-based zero trust. These are easy to conflate as "the same control" but they answer different questions, and both are needed for a defensible zero-trust posture.

## Options Considered

| Option | Pros | Cons |
|---|---|---|
| NetworkPolicy only (no mTLS) | Simple, native K8s, no extra components | Only controls *which pods can open a connection*, not *who is on the other end of it* — a compromised pod inside an allowed path can still speak plaintext to its allowed peers; doesn't satisfy requirement #4's identity-based zero trust |
| SPIFFE mTLS only (no NetworkPolicy) | Strong cryptographic peer identity | No network-layer containment — a misconfigured or compromised workload can still attempt connections to anything in the namespace, relying entirely on the app-level TLS handshake as the only gate; no defense-in-depth if that one control has a bug |
| Both, as independent layers: NetworkPolicy default-deny (who can *attempt* a connection) + SPIFFE mTLS via `common-lib` (who is *cryptographically proven* to be on each end) | Two independent controls that must both fail for a breach — matches actual zero-trust practice, not just its name | More to configure and verify (accepted — each layer is cheap to add once the other exists, and the delivery plan's Phase 6 DoD requires both to be independently tested) |

## Evidence

- This decision follows directly from the definition of zero trust the project's own requirements invoke ("no service trusts a caller without a verified SVID, independent of network location") — network-location controls (NetworkPolicy) and identity controls (mTLS/SPIFFE) are explicitly two different trust bases in that framing, so satisfying it requires both, not either.
- Consistent with ADR-0002's chosen mechanism: since mTLS is enforced by each service via `common-lib` rather than by a mesh's sidecar, NetworkPolicy is the *only* network-layer control in this architecture — there is no mesh-level policy layer to fall back on, making the NetworkPolicy default-deny posture non-optional, not a redundant nice-to-have.

## Decision

Both controls are mandatory and independently verified in Phase 6 of the delivery plan:
1. NetworkPolicies default-deny all ingress/egress in namespace `ecom`, with explicit allow rules matching the real call graph (gateway→services, services→Postgres, services→Kafka, services→RabbitMQ, services→Keycloak).
2. SPIFFE X.509-SVIDs (via SPIRE, ADR-0002) enforce mTLS at the application layer on top of whatever NetworkPolicy allows.

## Consequences

- Positive: a breach requires defeating two independent control types, not one; matches the literal zero-trust definition in the project requirements rather than a partial implementation of it.
- Negative / accepted trade-off: NetworkPolicy allow-lists must be kept in sync with the actual service call graph as it evolves — drift here silently reduces the network-layer control to "allow everything" without an alert. Recommend periodic `kubectl` policy audits (tracked under Phase 7's governance/observability work).
- Follow-up required: define the initial explicit allow-list before Phase 6 begins, based on the call graph documented in `doc/architecture/04-application-architecture.md`.

## Related

- Related architecture doc: `doc/architecture/05-technology-architecture.md`
- Related: ADR-0002 (zero-trust identity mechanism)
