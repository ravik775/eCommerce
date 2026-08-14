# ADR-0002: Zero-trust service identity — SPIRE, application-level mTLS, no service mesh

**Status**: Accepted (supersedes an earlier in-conversation direction of Linkerd + SPIRE)
**Date**: 2026-08-12
**Deciders**: Solution/Security Architect

## Context

Requirement #4: whether the application runs in Docker or Kubernetes, zero-trust must be enforced between microservices using SPIFFE — no service should trust a caller based on network location alone. This decision went through **two rounds of revision** in this engagement as more evidence came in; both rounds are recorded here for traceability rather than silently discarded.

## Options Considered

| Option | Pros | Cons |
|---|---|---|
| Istio + SPIRE (initial default instinct) | Most widely known service mesh; SPIFFE-compliant identity model | Heaviest control plane; highest latency overhead of the options benchmarked; industry-wide mesh adoption is declining, and Istio is the heaviest of the mesh options |
| Linkerd + SPIRE as external CA (first revision) | Lower latency than Istio in independent benchmarks; mTLS on by default with automatic rotation; lighter control plane than Istio | Still a second infrastructure control plane on top of SPIRE; still pays a proxy-hop latency cost; mesh adoption industry-wide is shrinking |
| Plain SPIRE, application-level mTLS via a shared `common-lib` module (final decision) | Exactly one new infrastructure component (SPIRE server + agent); zero proxying overhead — TLS terminates once, in the JVM; reuses this repo's existing `common-lib` convention, so it's one dependency add per service, not new infra per service; officially-maintained Java library exists for this exact purpose | mTLS wiring becomes application code (shared, but still code) rather than purely declarative infra config; a library version bump requires redeploying every service, not just upgrading a mesh control plane; no free L7 traffic-shaping features (acceptable — Resilience4j + the gateway already cover retries/circuit-breaking/rate-limiting) |

## Evidence

**Latency**
- Linkerd's Rust proxy: ~8ms median overhead, and "similar results and latencies are observed with mTLS enabled, showing that the mTLS feature does not have a significant impact" in one benchmark ([Buoyant gRPC load-balancing benchmark](https://www.buoyant.io/blog/benchmarking-grpc-load-balancing-on-kubernetes-linkerd-vs-istio-vs-cilium)).
- A separate, independent benchmark measured mTLS-specific latency increases of **166% for Istio, 33% for Linkerd, 99% for Cilium, 8% for Istio Ambient** — these numbers materially disagree with the Buoyant figures above, underscoring that mesh mTLS overhead is real and methodology-dependent, not negligible across the board. ([arXiv — Performance Comparison of Service Mesh Frameworks: the mTLS Test Case](https://arxiv.org/html/2411.02267v1))
- Application-level SPIRE integration (no proxy) is documented as adding **zero proxying overhead**, since the workload calls the Workload API and terminates TLS itself. ([Getting started with Envoy, SPIFFE, and Kubernetes](https://zoemccormick.medium.com/getting-started-with-envoy-spiffe-and-kubernetes-32f34cda5f35))

**Operational complexity**
- Hand-wiring Envoy's SDS integration to SPIRE per service is explicitly non-trivial: "there are many complex internals for the server and agent configurations." ([The Guide to Envoy SDS and SPIFFE SPIRE](https://greymatter.io/blog/deploying-and-configuring-the-edge-envoyproxy-to-communicate-with-mtls-through-spire/))
- Linkerd can be configured to consume SVIDs from a SPIRE deployment instead of its own bundled root CA, confirming the Linkerd+SPIRE combination is a real, documented pattern — but it is still two control planes, not one. ([SPIFFE Federation](https://spiffe.io/docs/latest/spiffe-specs/spiffe_federation/))

**Industry direction**
- CNCF's 2025 State of Cloud Native Development survey: developers running a service mesh fell from 18% (Q3 2023) to 8% (Q3 2025); the 2024 CNCF Annual Survey shows a drop from 50% to 42% year-on-year — the trend across the industry is away from full meshes, not toward them, for workloads that don't need mesh-scale traffic management. ([Buoyant — state of the service mesh](https://www.buoyant.io/blog/service-mesh-service-mesh-adoption-has-headroom-to-grow), [Tech Insider](https://tech-insider.org/what-a-service-mesh-actually-does-and-why-fewer-teams-want-one/))
- 62% of teams cited "resource overhead" as their primary frustration with service meshes — the exact cost this project avoids by not adding one. (same Tech Insider source)

**Feasibility of the chosen approach**
- `spiffe/java-spiffe` is the official SPIFFE-maintained Java library (core + provider + helper modules), published on Maven Central, with an active release history (last release within the past month at time of writing) — this is not an exotic or unmaintained dependency. ([java-spiffe GitHub](https://github.com/spiffe/java-spiffe), [java-spiffe-provider on Maven Central](https://central.sonatype.com/artifact/io.spiffe/java-spiffe-provider))
- `java-spiffe-provider` is designed to plug into the standard Java `Provider`/JSSE model, i.e. it integrates with normal `SSLContext`/`TrustManager`/`KeyManager` usage rather than requiring a bespoke TLS stack — tractable to wrap once in `common-lib`.

## Decision

Use **SPIRE only** (server + agent, no service mesh) to issue one SPIFFE X.509-SVID per Kubernetes ServiceAccount. Each service consumes its SVID directly through a new `spiffe-mtls` module added to the existing `common-lib`, built on the official `spiffe/java-spiffe` library, and enforces mTLS on both inbound (embedded Tomcat/Netty) and outbound (`RestTemplate`/`WebClient`) connections. No Istio, no Linkerd, no sidecar.

## Consequences

- Positive: exactly one new piece of infrastructure (SPIRE); zero proxy-hop latency cost, which matters given this project's explicit low-latency requirement; the mTLS wiring lives in one shared, versioned module consistent with how the rest of the repo already shares code.
- Negative / accepted trade-off: `common-lib`'s `spiffe-mtls` module becomes a dependency every service must redeploy against when it changes — this is a real coordination cost the mesh alternative would have avoided at the app layer (traded for lower infra operational cost instead).
- Follow-up required: if the service count grows enough that L7 traffic-shaping (canary routing, fine-grained per-route policy) becomes a real need beyond what Resilience4j + the gateway provide, this decision should be revisited — a service mesh is not ruled out forever, only deferred until its benefit outweighs its now-well-evidenced overhead.

## Reconfirmation (2026-08-14, start of Phase 6b)

Per the standing constraint on this phase, re-checked before writing any SPIRE integration code rather than assuming the earlier research still holds: `spiffe/java-spiffe` remains actively maintained (Maven Central releases as recent as April 2026), still the official SPIFFE-maintained Java Workload API client, still exposes `SSLContext`/Java Security Provider integration usable directly with Spring Boot's embedded-server TLS config. No dedicated Spring Boot starter for SPIFFE/SPIRE has emerged since this ADR was written — confirming the original reasoning that a thin `common-lib` wrapper was the right level of abstraction, not a gap this decision missed. **Decision unchanged.**

## Live deployment verification (2026-08-14, Phase 6b infrastructure)

SPIRE server + agent + SPIFFE CSI Driver deployed to the `spire` namespace (manifests: `k8s/base/spire/`) and brought to a genuinely stable state — `spire-agent` successfully node-attested with a real SPIFFE ID (`spiffe://ecommerce.local/spire/agent/k8s_psat/docker-desktop/...`) and is serving the Workload API; `spiffe-csi-driver` steady at 2/2 containers, zero restarts. Six real bugs were found and fixed via this live deployment, none of which were apparent from reading the manifests or SPIRE's own documentation in isolation:

1. **`k8sbundle` notifier plugin doesn't create its target ConfigMap, only patches it.** `spire-server` crash-looped with `configmaps "spire-bundle" not found` until it existed first.
2. **That same ConfigMap can't be a regular kustomize-managed resource.** Declaring it with placeholder empty data meant every `kubectl apply` reset it, wiping `spire-server`'s real published trust bundle and starving every agent's bundle-wait step. Fixed by making it a one-time `kubectl create` bootstrap step, deliberately excluded from the applied resource set going forward.
3. **`bitnami/kubectl:1.31` doesn't exist** — Bitnami's 2025/2026 image-distribution changes deprecated many free tags. Switched to `alpine/k8s:1.31.1`.
4. **RBAC gap**: `spire-agent`'s ServiceAccount had no permission to read ConfigMaps, so its bundle-wait init container's `kubectl get` failed with Forbidden on every loop iteration — silently, since the wait script's generic "waiting for trust bundle" echo made a permissions error look identical to a timing issue. Confirmed via `kubectl auth can-i get configmaps --as=system:serviceaccount:spire:spire-agent -n spire` returning `no`.
5. **`k8s_psat` node attestation needs an audience-scoped projected ServiceAccount token** at a specific path (`/var/run/secrets/tokens/spire-agent`) — the default automounted token (different path, no audience) doesn't satisfy it. Not mentioned as a mandatory volume in every example manifest encountered; found live via `unable to load token ... no such file or directory`.
6. **SPIFFE CSI Driver requires `-node-id`**, undocumented as mandatory in some example manifests — failed startup with `node ID is required` until populated from the Downward API (`spec.nodeName`).
7. **Socket path mismatch between the CSI driver and kubelet's registration path**: the driver wrote its own `csi.sock` to one hostPath while `node-driver-registrar` told kubelet to look for it at a *different* hostPath — registration failed every time with `no such file or directory` even though both containers were individually "healthy." Fixed by unifying both onto the single hostPath kubelet actually scans (`/var/lib/kubelet/plugins/csi.spiffe.io`).

None of these findings change the ADR's decision — they're implementation/deployment bugs in getting the already-correct architecture running, the same category of finding this project has repeatedly hit (and documented) when a design that's correct on paper meets a real environment (Phase 4/5's OAuth2 issues, Phase 6a's RabbitMQ/port bugs). Full detail and exact commands: `doc/k8-security-test.md`.

## Related

- Supersedes: the in-conversation "Linkerd + SPIRE as external CA" direction discussed before this ADR was written (never implemented, so no ADR was filed for it directly — recorded here as the rejected alternative instead).
- Related architecture doc: `doc/architecture/05-technology-architecture.md`
- `k8s/base/spire/` — server, agent, CSI driver manifests this verification covers.
- `doc/k8-security-test.md` — live test log with exact commands/output.
