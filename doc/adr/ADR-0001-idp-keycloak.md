# ADR-0001: Identity Provider — Keycloak brokering Google OIDC

**Status**: Accepted
**Date**: 2026-08-12
**Deciders**: Solution/Security Architect

## Context

Requirement: users sign up/log in with their Google account (OIDC), and the platform needs real RBAC (`CUSTOMER`/`ADMIN`/`SUPER_ADMIN` per Notes.md), auditable login events (SOC2), and a JWT that every backend service can validate without each service knowing about Google directly.

## Options Considered

| Option | Pros | Cons |
|---|---|---|
| Direct Google OAuth2 in the API Gateway (Spring Security OAuth2 Client only) | Least infrastructure — one fewer container; fastest to stand up | No central user/role store, no admin console, gateway would have to mint and manage its own downstream JWTs by hand; locked to Google as the only login option; harder to demonstrate SOC2-style access review/audit trail |
| Keycloak as Authorization Server, Google configured as a federated Identity Provider | Single JWT issuer all services trust; built-in realm/role/user management and admin console; federation model lets other IdPs (SAML, Microsoft Entra) be added later without touching app code; standard "identity broker" pattern | One more service to run and operate (mitigated: only added to the stack in the containerization phase, not before) |

## Evidence

- Keycloak's documented purpose is exactly this: "Keycloak acts as an identity broker, federating authentication to external identity providers... your applications only ever integrate with Keycloak" — the integration surface for every backend service stays constant even if the upstream IdP set changes. ([Phase Two — Keycloak as an Identity Provider Broker](https://phasetwo.io/docs/keycloak/idp/))
- Google-as-upstream-IdP is a first-class, documented Keycloak configuration path (register Keycloak as an OAuth client in Google's developer console, configure under Keycloak's Identity Providers section), not a custom integration. ([Identity Federation Made Easy — Keycloak](https://walkingtree.tech/identity-federation-made-easy-integrating-keycloak-seamless-authentication/))
- Keycloak's "First Broker Login Flow" natively handles linking a federated Google identity to a local Keycloak user record — needed for the user-service profile linkage in Phase 4 of the delivery plan. ([Keycloak Identity Provider Federation](https://ashishsrivastav.com/blog/keycloak-identity-provider-federation))
- Documented enterprise pattern explicitly matches this use case: "single entry point for diverse user bases... customers via social logins" — this project's Google-login-for-customers requirement is the textbook case, not an edge case. (same source as above)

## Decision

Keycloak is the Authorization Server for the platform (realm `ecom`), with Google configured as a federated Identity Provider under that realm. The API Gateway performs the OIDC Authorization Code flow against Keycloak (not Google directly); Keycloak-issued JWTs are the single trust anchor every backend service validates.

## Consequences

- Positive: one issuer to trust/rotate/revoke across all services; real role management without custom code; not locked into Google if another login method is needed later.
- Negative / accepted trade-off: Keycloak is a new operational component — deferred to the Docker Compose phase (Phase 5) and Kubernetes phase (Phase 6) of the delivery plan, not stood up before it's needed.
- Follow-up required: define the `ecom` realm's role mapping (`CUSTOMER`/`ADMIN`/`SUPER_ADMIN`) and confirm Google OAuth client credentials are stored as Kubernetes Secrets, never in committed config (see ADR-0006 and Phase 7 of the delivery plan).

## Related

- Related architecture doc: `doc/architecture/05-technology-architecture.md`
