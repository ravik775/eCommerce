# ADR-0005: API Gateway responsibilities — routing, rate limiting, JWT validation boundary

**Status**: Accepted
**Date**: 2026-08-12
**Deciders**: Solution/Security Architect

## Context

The gateway audit found routing, rate limiting, and circuit-breaker configuration present only as three redundant, mutually-inconsistent, commented-out YAML drafts (`api-gateway.yml`, `api-gateway2.yml`, `api-gateway-ratelimiter.yml`) in config-server — none active. A decision is needed on what the gateway is responsible for versus what belongs to each backend service, so the drafts can be consolidated into one canonical, enforced config instead of three abandoned experiments.

## Options Considered

| Option | Pros | Cons |
|---|---|---|
| Gateway does only routing; each service independently validates JWTs and applies its own rate limits | No single point of failure for auth | Duplicated JWT validation logic and rate-limit config across 5+ services; inconsistent enforcement risk if one service's config drifts |
| Gateway is the sole authority for authN and rate limiting; backend services trust the gateway implicitly (no per-service validation) | Simplest to configure once | Violates zero trust directly — any workload that reaches a backend service on the internal network bypasses auth entirely; contradicts requirement #4 |
| Gateway handles edge concerns (routing, coarse rate limiting, initial JWT validation as a resource server); every backend service **also** validates the JWT independently as its own resource server (defense in depth) | Matches zero-trust requirement — no service trusts a caller by network position alone; consistent with Phase 4/6 of the delivery plan (OIDC + SPIFFE mTLS both enforced at the service, not just the edge) | Slightly more config per service (each needs its own OAuth2 resource-server config pointing at the same Keycloak issuer) — accepted, this is the same "defense in depth" principle already applied to mTLS in ADR-0002 |

## Decision

The API Gateway is responsible for: request routing to Eureka-registered services, edge-level rate limiting (Redis token bucket, using the already-drafted `userKeyResolver` bean), and terminating the browser-facing OIDC login flow, minting/relaying the JWT downstream. It is **not** the sole point of authorization — every backend service independently validates the same Keycloak-issued JWT as an OAuth2 resource server (Phase 4), consistent with the zero-trust posture established in ADR-0002 (a request that somehow bypasses the gateway must still be rejected by the service it reaches).

## Consequences

- Positive: no single point of bypass for authorization; rate limiting protects the edge without requiring every service to reimplement it; consolidates three conflicting draft configs into one canonical, testable one.
- Negative / accepted trade-off: JWT validation config (issuer URI, expected audience) is duplicated across services — acceptable, it's declarative config (a few lines of `application.yml`), not logic duplication.
- Follow-up required: consolidate `api-gateway.yml`/`api-gateway2.yml`/`api-gateway-ratelimiter.yml` into one file as part of Phase 1 of the delivery plan; this ADR is what that consolidation should conform to.

## Related

- Related architecture doc: `doc/architecture/04-application-architecture.md`, `doc/architecture/05-technology-architecture.md`
