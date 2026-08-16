# ADR-0013: Edge WAF — ModSecurity + OWASP CRS via ingress-nginx (no standalone component)

**Status**: Accepted — implemented 2026-08-16
**Date**: 2026-08-13
**Deciders**: Solution/Security Architect

**2026-08-16 update**: implemented via `k8s/base/ingress-nginx.yaml` — a hand-trimmed `ingress-nginx` controller deployment (own namespace, to avoid the `ecom` namespace's already-tight `ResourceQuota`), `enable-modsecurity`/`enable-owasp-modsecurity-crs` set exactly as this ADR's Decision specified, plus an `Ingress` routing to `api-gateway`. One addition beyond the original Decision: `SecRuleEngine DetectionOnly` (not blocking) to start — a WAF that's never seen this application's real traffic risks false-positive blocking on day one; flip to enforcing once a burn-in period confirms no false positives. Verified live: controller pod running, readiness passing.

## Context

A genuinely production-grade, internet-facing posture calls for a WAF in front of the API Gateway, catching common attack patterns (SQLi/XSS payloads, known-bad request signatures) before they reach application code. This must be weighed against the project's explicit "add infrastructure only when needed" principle.

## Options Considered

| Option | New infrastructure? | Fit |
|---|---|---|
| ModSecurity + OWASP CRS as ingress-nginx annotations | None — rides on the Ingress controller already required in Phase 6 to terminate the `api-gateway` Ingress | Best fit: real WAF capability, zero net-new components |
| Cloud-managed WAF (AWS WAF, Cloudflare, Azure Front Door) | A managed edge service, but requires cloud-provider fronting | Rejected: contradicts requirement #5 (Postgres and the deployment stay self-hosted in-cluster, not behind a cloud-managed edge) |
| Standalone WAF proxy (e.g., Coraza) as its own Deployment | Yes — new Deployment/Service, own scaling and lifecycle | Rejected for now: strictly more operational surface than option 1 for the same capability |

## Decision

Enable ModSecurity with the OWASP Core Rule Set on ingress-nginx via its standard ConfigMap flags (`enable-modsecurity: "true"`, `enable-owasp-modsecurity-crs: "true"`) when the Ingress controller is deployed in Phase 6. No standalone WAF component is introduced.

## Consequences

- Positive: real WAF coverage (OWASP CRS) with zero additional Pods/Services to operate; naturally scales with the Ingress controller.
- Negative / accepted trade-off: WAF rule tuning is scoped to whatever ingress-nginx's ModSecurity integration supports — less flexible than a dedicated WAF proxy if very custom rules are ever needed. Revisit if that becomes a real requirement.
- Follow-up required: enable and tune CRS paranoia level as part of Phase 6's Ingress manifest; false-positive tuning happens once real traffic patterns exist.

## Related

- Related architecture doc: `doc/architecture/05-technology-architecture.md`
- ADR-0006 (NetworkPolicy + SPIFFE mTLS) — the WAF is a third, independent layer in front of those two
