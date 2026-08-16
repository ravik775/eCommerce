# ADR-0042: Edge TLS — production options, and why it's not implemented locally

**Status**: Accepted
**Date**: 2026-08-16
**Deciders**: Solution/Security Architect

## Context

ADR-0037's SOC 2 mapping named the biggest concrete Confidentiality gap: the gateway is reached over plain HTTP in this local deployment, with no TLS-termination layer anywhere in `k8s/base/`. This ADR documents the real production options and — the specific ask — checks whether any of them are actually feasible to run today, on this local, resource-constrained Docker Desktop cluster, now that `ingress-nginx` exists (this session's WAF work, ADR-0013) as the natural place to terminate TLS.

## Options Considered

| Option | How it works | Local feasibility |
|---|---|---|
| **cert-manager + Let's Encrypt (ACME)** | cert-manager watches Ingress resources, automatically requests/renews certs from a real CA | **Not feasible locally.** ACME's HTTP-01/DNS-01 challenges require a publicly resolvable domain the CA can reach to prove control — `localhost`/a local Docker Desktop cluster has neither. This is the correct production choice, not a local one. |
| **cert-manager + self-signed `Issuer`** | cert-manager still automates cert issuance/rotation, but from an in-cluster self-signed CA instead of a public one | **Feasible locally**, but adds 3 more pods (controller, webhook, cainjector) — real additional resource cost (typically ~150-250Mi combined) for automation that, for a single static local cert, doesn't buy much over doing it once by hand. |
| **A single hand-generated self-signed cert, mounted as a Secret, referenced by the Ingress `tls:` block** | One `openssl req -x509` command, one `kubectl create secret tls` | **Feasible locally, cheapest option.** No new controller pods; the trade-off is manual renewal (self-signed certs are typically issued with a long validity, e.g. 365 days, so this is a rare, not recurring, chore) |
| **No edge TLS at all (current state)** | — | Feasible (it's what's running today) but is the actual gap this ADR responds to |

## Evidence

- cert-manager's own documentation confirms ACME issuers work by "monitoring ingress resources" and require a reachable challenge endpoint — consistent with the "not feasible without a public domain" conclusion above, not a project-specific limitation.
- Namespace resource headroom, confirmed live via `kubectl describe resourcequota ecom-quota -n ecom` immediately before this decision: `requests.memory 3904Mi/5Gi used` (≈76%), leaving roughly 1.2Gi of quota headroom in the `ecom` namespace specifically — cert-manager's 3-pod footprint would consume a meaningful fraction of that remaining headroom for a benefit (automated *renewal*) that doesn't materially matter for a single long-lived self-signed cert. (`ingress-nginx` itself was deployed in its own namespace, outside this quota, precisely to avoid this exact pressure — seen in ADR-0013's implementation this session.)
- A self-signed cert's validity can be set arbitrarily long (e.g. 10 years) for a purely local, non-public-facing dev cluster where the only consumers are developers who already have to click through a browser trust warning anyway — the automation cert-manager provides has genuinely low marginal value here specifically.

## Decision

**Production**: cert-manager + Let's Encrypt (ACME, `HTTP-01` or `DNS-01` challenge depending on whether the production ingress is publicly reachable) is the correct, standard choice — implement it when a real domain and real production ingress exist. This is deferred, not decided against; it simply has no target to attach to yet (same "no premature infrastructure" reasoning as ADR-0002/0013/0014/0015/0021/0022/0034/0039).

**Local**: **not implemented now.** Given the resource-headroom math above and that a local cluster has no real confidentiality requirement its developers don't already have alternate access to (the whole cluster runs on one machine), the cost (cert-manager's 3 extra pods, or even the manual self-signed-cert path's ongoing upkeep) isn't justified purely to close a documentation gap that has no real attacker between "the developer's own machine" and "the developer's own machine." If a quick local TLS smoke-test is ever needed (e.g. to verify the application handles HTTPS redirects/headers correctly before a real deployment), the hand-generated self-signed cert + Ingress `tls:` block is the documented path — a single `openssl req -x509 -nodes -days 3650 -newkey rsa:2048 ...` command plus a `kubectl create secret tls` — not cert-manager.

## Consequences

- Positive: the production path is fully specified (cert-manager + ACME) and the local non-implementation is a reasoned decision, not silence — matches this project's consistent pattern of naming deferred infrastructure explicitly rather than leaving a gap unexplained.
- Negative / accepted trade-off: this local cluster continues to serve plain HTTP through `ingress-nginx` — genuinely fine for a single-developer-machine deployment, but means "the app works correctly over HTTPS" is never verified until a real deployment, which is a real (small, accepted) integration-risk gap.
- Follow-up required: when a real (non-`localhost`) deployment target exists, install cert-manager + a Let's Encrypt `ClusterIssuer` and add `tls:` blocks to the Ingress resources already created this session (`k8s/base/ingress-nginx.yaml`) — the Ingress infrastructure itself is already in place, only the issuer/cert wiring is deferred.

## Related

- Related: ADR-0013 (WAF — implemented this session via the same `ingress-nginx` this ADR builds on), ADR-0037 (SOC 2 mapping — the gap this ADR responds to), ADR-0002/0021 (this project's consistent "no premature infrastructure" pattern)

Sources:
- [cert-manager documentation — securing NGINX Ingress](https://cert-manager.io/docs/tutorials/acme/nginx-ingress/)
- [ingress-nginx TLS/HTTPS user guide](https://kubernetes.github.io/ingress-nginx/user-guide/tls/)
