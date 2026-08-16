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

## 2026-08-16 addendum: local-dev egress-scoping gap and production options

An architecture review flagged that `k8s/base/networkpolicy.yaml`'s local-dev egress rule for Postgres/Kafka/Keycloak (which run as standalone host containers, not pods, in this local environment) uses a broad `ipBlock` against the Docker Desktop host-gateway IP rather than per-service `podSelector` scoping — any pod allowed that egress can reach all three services' ports, not just the one it actually needs. This was already self-documented in the manifest's own "LOCAL-DEV DEVIATION" comment, not a silent gap, but it had no options-researched closure path on record.

**The gap mostly self-resolves by design, not by new engineering.** ADR-0022 already establishes that the real deployment target runs Postgres (and, by the same reasoning, Kafka/Keycloak) **in-cluster**, at which point `networkpolicy.yaml`'s existing `podSelector`-based rules (the same pattern already used for every pod-to-pod rule in this file) apply automatically — the broad `ipBlock` rule is purely an artifact of these three components running *outside* K8s on this specific dev machine, not a limitation of the NetworkPolicy design itself. **The primary closure path is simply: deploy the target-state manifests** (`postgres.yaml`, `kafka.yaml`, `keycloak.yaml` — already written, just excluded from `kustomization.yaml`'s local resource list) **instead of writing new policy.**

**If a real deployment ever still needs to reach a store outside the cluster** (e.g., a managed cloud Postgres/MSK instead of in-cluster), plain Kubernetes `NetworkPolicy` has no way to scope by DNS name — only by IP/CIDR, the same limitation causing today's local-dev gap. Options researched for that scenario:

| Option | Capability | Cost |
|---|---|---|
| Stay on plain `NetworkPolicy` + `ipBlock` (current mechanism) | IP/CIDR-only scoping; correct once the target's IP range is known and stable (typical for a managed cloud DB with a fixed private-subnet CIDR) | Zero new infrastructure |
| Migrate CNI to **Cilium**, use `CiliumNetworkPolicy` FQDN rules | Cilium's DNS-aware proxy allows egress rules by hostname (e.g. `*.rds.amazonaws.com`) instead of IP, solving the exact class of problem here | A CNI migration — real infrastructure change, not a config tweak; per 2026 CNCF survey data, Cilium is now the most widely deployed CNI in production, so this isn't a niche choice, but it's still a bigger lift than the "just deploy in-cluster" path above |
| Service-mesh egress gateway (Istio/Linkerd) | Centralizes and audits all egress through one controlled point, with its own FQDN-based policy | Heaviest option — a full mesh sidecar/ambient deployment for a benefit this project's SPIFFE-mTLS-without-a-mesh architecture (ADR-0002, ADR-0006's own Decision) deliberately avoided elsewhere; inconsistent with that existing choice unless the mesh is adopted for other reasons too |

**Decision**: no new implementation now — the existing plan (run these three components in-cluster for any real deployment) already closes this correctly, and is cheaper than adopting Cilium or a mesh purely for this. Revisit the Cilium option specifically if a future deployment target genuinely needs an out-of-cluster stateful dependency (e.g., a managed cloud database chosen for operational reasons) rather than an in-cluster one.

Sources: [Kubernetes Native FQDN Based Egress Network Policies](https://sourcehawk.medium.com/kubernetes-native-fqdn-based-egress-network-policies-cc44105ad138), [Cilium FQDN/DNS-based policies documentation](https://docs.cilium.io/en/latest/security/dns/), [CNCF Annual Survey 2025 CNI adoption data (via OneUptime's 2026 Cilium writeup)](https://oneuptime.com/blog/post/2026-01-27-cilium-network-policies/view)
