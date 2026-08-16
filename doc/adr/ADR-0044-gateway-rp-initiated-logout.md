# ADR-0044: Gateway RP-initiated logout — closing a stale Keycloak SSO session bug

**Status**: Accepted
**Date**: 2026-08-16
**Deciders**: Solution/Security Architect

## Context

ADR-0005 established the gateway as the OIDC login boundary; neither it nor any other ADR specified what logout actually does beyond "the user is logged out." In practice, `SecurityConfig` had no explicit `.logout()` configuration at all, so Spring Security's default `/logout` handling only invalidated the gateway's own local session — Keycloak's SSO session (the `KEYCLOAK_SESSION` cookie on the Keycloak host) stayed alive. The practical, user-visible symptom: logging out and logging back in as a *different* user silently re-authenticated as whoever was previously signed in, with no credentials prompt at all — found live this session while testing `admin1`'s login.

## Why the obvious fix (`OidcClientInitiatedServerLogoutSuccessHandler`) doesn't work here

Spring Security's standard RP-initiated-logout handler reads the Keycloak `end_session_endpoint` from `ClientRegistration`'s `configurationMetadata` map — which is populated **only** via OIDC discovery (`issuer-uri`). This deployment deliberately does not use `issuer-uri` (see `api-gateway.yml`'s own comment: a single discovery-fetch target can't simultaneously be browser-reachable, for the `iss` claim on tokens the login flow produces, and container-reachable, for the gateway's own server-to-server calls, on this network topology — attempted once, crashed the gateway's `ApplicationContext` with a real `Connection refused`). Every OAuth2 endpoint here is wired manually instead (`authorization-uri` browser-facing, `token-uri`/`jwk-set-uri`/`user-info-uri` container-facing). Consequence: `configurationMetadata` is always empty, so `OidcClientInitiatedServerLogoutSuccessHandler` silently falls back to a local-only logout — the exact bug being fixed, not a fix for it.

## Decision

A hand-written `ServerLogoutSuccessHandler` in `SecurityConfig`, matching this deployment's existing "manual endpoint, not discovery" pattern rather than fighting it:
1. Reads the current session's `OidcUser.getIdToken()` (if present) for `id_token_hint`.
2. Builds the Keycloak logout URL from a new `keycloak.logout-uri` property (browser-reachable host, same reasoning as `authorization-uri` — the browser is what gets redirected, not the gateway) plus `client_id` and a `post_logout_redirect_uri` derived from the actual incoming request's scheme/authority.
3. Issues a 302 redirect to it directly.

Requires `post.logout.redirect.uris` to be registered on the `ecommerce-gateway` Keycloak client (added to `ecom-realm.json.template`) — Keycloak rejects an unregistered redirect with `"Invalid redirect uri"`, found live as the first symptom while debugging this.

## Regression guard

No automated test covers this end-to-end (an OIDC redirect flow through a real Keycloak instance is integration-test territory this project doesn't currently have infrastructure for — see the honest test-coverage gap noted in `README.md`'s Code Quality section). The regression check that caught this bug the first time, and the one to re-run before any change to `SecurityConfig`'s logout handling:

1. Log in as any user.
2. Log out via the UI.
3. Confirm the browser lands on Keycloak's own login form (username/password fields), **not** directly back in the app.
4. Log in as a *different* user.
5. Confirm the app shows the new user's identity, not the previous session's.

Step 3 is the critical assertion — if logout silently succeeds locally but the SSO session survives, step 5 fails with no error at any layer, which is exactly why this bug went unnoticed until specifically tested for.

## Consequences

- Positive: logout now actually terminates the Keycloak SSO session; a second user can log in on the same browser without seeing the first user's session.
- Negative / accepted trade-off: the hand-written handler duplicates a small amount of what `OidcClientInitiatedServerLogoutSuccessHandler` would otherwise provide for free (a well-tested Spring Security class) — accepted because the alternative (switching to `issuer-uri` discovery) reopens the exact `ApplicationContext` crash this deployment's manual-endpoint pattern was chosen to avoid.
- Follow-up required: if this deployment ever moves to a single reachable Keycloak host (browser and container-facing addresses converge — e.g. a real production DNS name reachable from both), revisit switching to `issuer-uri` + `OidcClientInitiatedServerLogoutSuccessHandler`, since the manual-endpoint constraint that forced this workaround would no longer apply.

## Related

- Related: ADR-0005 (API Gateway boundary — the login flow this closes the logout half of), `ecom-realm.json.template` (the `post.logout.redirect.uris` registration this fix also required)
