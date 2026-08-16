# ADR-0046: Gateway OAuth2 login failure handling — distinguishing "already logged in" from a real credential failure

**Status**: Accepted
**Date**: 2026-08-16 14:46 IST
**Deciders**: Solution/Security Architect

## Context

Reported live: clicking the "Google" login button while a Keycloak SSO session already existed in the browser produced a confusing failure that read like "invalid credentials." Root-caused via Keycloak's own event log and source (`services/src/main/java/org/keycloak/services/resources/SessionCodeChecks.java`):

Every rendered login page embeds a one-time `session_code` tied to a specific server-side auth session. If the underlying browser SSO session gets established through some other path (a second tab, a page left open) before that page's login link is clicked, Keycloak treats the click as replaying an already-completed auth session and rejects it with `LoginProtocol.Error.ALREADY_LOGGED_IN`. Confirmed via a real log line that this is sent back to the gateway as a normal OAuth2 callback error, not shown as a Keycloak-hosted page:

```
type="IDENTITY_PROVIDER_LOGIN_ERROR", clientId="ecommerce-gateway", error="already_logged_in",
identity_provider="google", redirect_uri="http://localhost:8080/login/oauth2/code/keycloak",
redirected_to_client="true"
```

`SecurityConfig`'s `.oauth2Login(...)` had never configured an `.authenticationFailureHandler(...)` (established in ADR-0005/ADR-0017, neither of which anticipated this specific failure mode). Spring Security's *default* OAuth2 login failure handling doesn't discriminate by error code — a genuinely non-failure condition ("you're already authenticated") surfaced as an undifferentiated, misleading error indistinguishable from a real bad-credentials failure.

## Options Considered

| Option | Fit |
|---|---|
| Leave default Spring Security failure handling | Rejected — the actual reported bug; a non-failure condition reads as a credential error |
| Custom handler that special-cases `already_logged_in` by redirecting to `/` (re-triggering auth, which then completes silently since the SSO cookie is genuinely valid), and surfaces any other error code distinctly | **Chosen** |
| Prevent the stale-page scenario entirely (e.g., force `prompt=login` on every authorization request) | Rejected for now — would force a fresh login screen on every visit even when SSO should legitimately work silently, defeating the purpose of SSO; the failure-handler fix addresses the actual symptom without disabling SSO's normal behavior |

## Decision

Added `oauth2LoginFailureHandler()` to `SecurityConfig`, wired via `.oauth2Login(oauth2 -> oauth2....authenticationFailureHandler(...))`. For `OAuth2AuthenticationException` with error code `already_logged_in`: redirect to `/` — since no gateway-local session exists yet at that point, this redirect re-triggers the standard unauthenticated-request flow, which generates a fresh `session_code` and, because the Keycloak SSO cookie really is valid, completes without ever showing a form. Any other OAuth2 error code is surfaced via a `login_error` query parameter rather than swallowed the same way.

**Verified live** (2026-08-16, ~14:35–14:45 IST): reproduced the exact failure condition with two browser tabs — one holding a stale login page's Google link (captured `session_code`), a second tab used to complete a real password login first (establishing the SSO cookie). Clicking the stale tab's Google link against the fixed gateway resulted in the tab silently ending up authenticated (`GET /user/me` returned a valid user, no error shown) — confirming the redirect-and-retry path works as designed.

## Consequences

- Positive: closes a real, reproducible UX bug with evidence-based root cause, not a guess; SSO's normal silent-reauth behavior is preserved for the common case.
- Negative / accepted trade-off: a user who triggers `already_logged_in` experiences one extra redirect hop before landing in the app — imperceptible in practice (confirmed live), but technically not a single-redirect flow anymore for that specific edge case.
- Follow-up required: none currently open. If a genuinely different OAuth2 error code is ever reported as similarly non-actionable/confusing, extend the same special-case pattern in `oauth2LoginFailureHandler()` rather than adding a second, differently-shaped handler.

## Related

- Related: ADR-0005 (API Gateway boundary — the login flow this failure handler is part of), ADR-0017 (PKCE), ADR-0044 (the RP-initiated logout fix — same session's other Keycloak-session-lifecycle bug, found and fixed the same way: live evidence first, then a targeted fix)
