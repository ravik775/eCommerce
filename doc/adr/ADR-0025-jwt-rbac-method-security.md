# ADR-0025: Token format (JWT) and RBAC enforcement (Spring Security method security on realm roles)

**Status**: Accepted (design only — implementation is Phase 4, not yet built; see `doc/architecture/07-migration-planning.md`)
**Date**: 2026-08-13
**Deciders**: Solution/Security Architect

## Context

ADR-0001 decided Keycloak as the Authorization Server; ADR-0005 decided every service validates the token independently (defense in depth), not just the gateway; ADR-0017 decided PKCE for the SPA's login flow. None of those ADRs pinned down two remaining, genuinely separate questions: (1) is the access token a JWT or an opaque reference, and (2) mechanically, how does a service turn that token into an enforced role check on a specific method (`CUSTOMER`/`ADMIN`/`SUPER_ADMIN`, per Notes.md and `user-service`'s existing `Role` enum)? This ADR closes both gaps explicitly rather than leaving them implied.

## Decision 1 — Token format: JWT, not opaque

| Option | Validation cost per request | Revocation |
|---|---|---|
| JWT (self-contained, signed) | Local signature + claims check against Keycloak's published JWKS — no network call to Keycloak on the hot path | Cannot revoke a live JWT directly; mitigated by short access-token lifetimes + refresh-token rotation (Keycloak default pattern) |
| Opaque token + introspection | Every validating service calls Keycloak's introspection endpoint on every request | Immediate — revoking server-side takes effect instantly |

**Chosen: JWT.** "JWTs are best when an API needs local verification at scale, where the resource server can validate the signature, inspect claims, and make an allow/deny decision without calling a central authorization service on every request... useful for distributed systems, gateways, and microservices." ([NHI FAQ — JWT vs opaque](https://nhimg.org/faq/how-should-security-teams-choose-between-jwts-and-opaque-tokens-for-apis/)) Given ADR-0005's defense-in-depth model has *every* service (gateway + all 5 backend services) validating the token independently, an introspection call per service per request would multiply load on Keycloak by the number of services in the call path — a real cost JWT avoids entirely. The accepted trade-off (no instant revocation) is mitigated the standard way: short-lived access tokens with refresh-token rotation, not a denylist — kept simple, no new infrastructure.

## Decision 2 — RBAC enforcement: Spring Security method security, reading Keycloak realm roles

Keycloak's JWT does not put roles where Spring Security looks by default: "roles must be of a particular format... Keycloak defines roles using its own structure [`realm_access.roles`]... without a custom `JwtGrantedAuthoritiesConverter`, Spring Security only reads the `scope` claim." ([Medium — Solving JWT Role Mapping Issues](https://medium.com/@mohammad.h.zbib/solving-jwt-role-mapping-issues-in-spring-boot-with-keycloak-3f40db57216e)) This is a real, well-documented integration gap that must be explicitly closed, not assumed to work out of the box.

**Decision**: each service (as an OAuth2 resource server) registers a custom `JwtAuthenticationConverter` that reads `realm_access.roles` from the token and maps each role to a `ROLE_<name>` `GrantedAuthority` — the standard pattern Spring Security's `hasRole(...)` expects. `@EnableMethodSecurity` is enabled per service, and endpoints are annotated directly, e.g. `@PreAuthorize("hasRole('ADMIN')")` on `catalog-service`'s `POST /products`. This keeps the authorization check next to the method it protects (readable, testable in isolation) rather than in a separate, easy-to-drift routing table.

## Consequences

- Positive: closes two real, specific implementation gaps (token format, and the documented Keycloak-role-mapping pitfall) that the earlier ADRs left implicit; RBAC checks are co-located with the code they protect, not a separate configuration surface that can silently drift out of sync with the endpoints it's meant to cover.
- Negative / accepted trade-off: no instant token revocation — accepted per Decision 1, mitigated by short token lifetimes (to be set when Keycloak's realm is configured in Phase 4), not by adding a denylist/introspection component.
- Follow-up required (Phase 4, not started): configure the `ecom` realm's roles to match `user-service`'s existing `Role` enum (`CUSTOMER`/`ADMIN`/`SUPER_ADMIN`); implement the shared `JwtAuthenticationConverter` (candidate for `common-lib`, since every service needs the identical mapping — same reasoning as ADR-0023's shared filter); annotate the actual admin-only endpoints (`catalog-service`'s product mutations at minimum) with `@PreAuthorize`.

## Related

- ADR-0001 (Keycloak/OIDC IdP), ADR-0005 (defense-in-depth JWT validation boundary), ADR-0017 (PKCE for the SPA)
- Related architecture doc: `doc/architecture/02-business-architecture.md` (role definitions), `doc/architecture/04-application-architecture.md`
