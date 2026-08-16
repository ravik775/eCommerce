# ADR-0049: Gateway session absolute max-age — bounding stale Keycloak role grants

**Status**: Accepted
**Date**: 2026-08-16 17:35 IST
**Deciders**: Solution/Security Architect

## Context

Reported: `ravik775@gmail.com` had `CAN_TRACE` assigned in Keycloak, but the UI still only showed `CUSTOMER` permissions — the Settings menu stayed hidden.

Root-caused, not reproduced as a defect: `KeycloakOidcUserService.loadUser()` (`api-gateway/src/main/java/org/bgm/apigateway/config/KeycloakOidcUserService.java`) maps Keycloak's `realm_access.roles` claim into `ROLE_*` authorities exactly once, at initial OAuth2 login, and Spring Security caches the resulting `DefaultOidcUser` (authorities baked in) in the gateway's server-side session for the session's entire lifetime. Neither a silent OAuth2 access-token refresh nor ordinary continued use of the app ever re-invokes `loadUser()` or otherwise re-derives authorities. This is standard Spring Security OAuth2-login behavior, not specific to this codebase — but it means a role granted (or revoked) in Keycloak after a user's browser session started has no way to reach that session until the user happens to log out and back in.

With no explicit session timeout configured (`api-gateway` runs on Spring WebFlux's default ~30-minute *inactivity* timeout), a continuously-active session's stale authority snapshot can in principle persist indefinitely — inactivity timeout alone doesn't bound this, since it never fires for an actively-used session.

## Decision

Add `SessionMaxAgeGatewayFilter` (`api-gateway/src/main/java/org/bgm/apigateway/config/SessionMaxAgeGatewayFilter.java`) — a `GlobalFilter` that reads the session's `OidcUser.getIdToken().getIssuedAt()` via `ReactiveSecurityContextHolder` and, once that exceeds `security.session.max-age` (default `PT30M`, configured in `config-server/src/main/resources/config-repo/api-gateway.yml`), invalidates the gateway's local `WebSession` and redirects back to the same path — regardless of whether the session has been idle or continuously active.

Deliberately does **not** also terminate the Keycloak SSO session (unlike the RP-initiated logout handler in `SecurityConfig`): with the local session gone but the Keycloak `KEYCLOAK_SESSION` cookie still valid, the redirect re-triggers `oauth2Login`, which completes silently (no login form) and re-runs `KeycloakOidcUserService.loadUser()` — picking up whatever roles Keycloak currently has on file. This is the same "no local session + valid Keycloak SSO session = transparent re-authentication" mechanism `oauth2LoginFailureHandler`'s `already_logged_in` handling already relies on (see the `ForceTraceFilter`/`CorrelationTraceGatewayFilter` line of ADRs — this reuses an existing, already-verified codebase pattern rather than introducing a new one).

30 minutes was chosen to roughly match this realm's typical SSO idle window, so the forced re-login usually lands inside an already-valid Keycloak SSO session and stays invisible to the user; a role change can lag an active session by at most that window.

### Why not the alternatives considered

- **Re-run `loadUser()` on every request or on token refresh**: would fully close the staleness window, but requires hooking Spring's `ReactiveOAuth2AuthorizedClientManager` refresh path to also update the stored principal's authorities — meaningfully more code and harder to unit test than a single filter checking a timestamp, for a gap whose worst case is now bounded to 30 minutes. Rejected per this project's standing "prefer simplicity" bias (see ADR-0047).
- **Shorten inactivity timeout only**: doesn't help — a session that stays continuously active never idles out, and that's exactly the case this ADR needs to bound.
- **Document only, no code change**: rejected — "update of permission is common" per the live report, so relying on every admin remembering to tell every affected user to log out doesn't scale and isn't the kind of thing this project's audit trail should depend on.

## Regression guard

`SessionMaxAgeGatewayFilterTest` (4 cases) unit-tests `isStale(Authentication)` directly, made package-private for exactly this purpose — same "test the decision logic in isolation" split ADR-0048 established for `CorrelationTraceGatewayFilter.callerHasCanTraceRole`: session younger than max-age, session older than max-age, a non-OIDC `Authentication` (never stale), and a null `Authentication` (never stale). Writing this test caught a real bug before it shipped: the session's `Authentication` is an `OAuth2AuthenticationToken` whose *principal* is the `OidcUser`, not an `OidcUser` itself — an `authentication instanceof OidcUser` check (the first version of this code) is always `false` in production, silently disabling the whole filter. Fixed to check `authentication.getPrincipal() instanceof OidcUser` instead.

The surrounding reactive/session-invalidation wiring (`filter(...)`, `forceReauth(...)`) is the same kind of thing this project has previously chosen to verify live rather than unit-test in isolation (see ADR-0048's regression-guard section for the same reasoning applied to `CorrelationTraceGatewayFilter`): log in, wait past `security.session.max-age` (or temporarily lower it), make a request, confirm a silent redirect completes and `GET /user/me` reflects any role change made in Keycloak during the wait.

## Consequences

- Positive: bounds a real, reported staleness gap to a fixed, configurable window without new infrastructure, reusing an authentication pattern already proven live elsewhere in this codebase. The unit tests also caught a real `instanceof` bug (see Regression guard) before it reached a live environment.
- Negative / accepted trade-off: an active user is silently redirected (imperceptible in the common case where Keycloak's SSO session is still valid) every `security.session.max-age`; if the Keycloak SSO session itself has expired in that window, the user sees a real login prompt instead of a silent round-trip — an acceptable, rare edge case.
- Follow-up required: live end-to-end verification (see Regression guard) once redeployed.

## Related

- Related: ADR-0048 (server-side CAN_TRACE enforcement — the permission this staleness bug specifically affected), ADR-0025 (JWT/RBAC — general role-enforcement pattern), ADR-0044 (RP-initiated logout — the Keycloak-session-termination pattern this ADR deliberately does *not* reuse, and why), ADR-0032 (original `CAN_TRACE` toggle decision)
