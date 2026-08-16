# ADR-0050: Sync a returning user's roles from Keycloak on every login, not just at creation

**Status**: Accepted
**Date**: 2026-08-16 20:16 IST
**Deciders**: Solution/Security Architect

## Context

Reported: `ravik775@gmail.com` was granted `CAN_TRACE` in Keycloak, but `GET /user/me` kept returning only `CUSTOMER` — repeatedly, across multiple fresh logins and even after two full gateway redeploys (which wipe all in-memory sessions, forcing a genuinely new OAuth2 login each time).

Investigated and ruled out, in order:
1. **Stale browser session** — ruled out once two gateway redeploys had already wiped all sessions; the problem persisted regardless.
2. **Client-side JS caching** (`ui/src/app.js` caches `/user/me`'s response in a JS variable at page load) — plausible but not verified as the actual cause once a direct `curl GET /user/me` (bypassing the browser entirely) still returned `{"roles": ["CUSTOMER"]}`.
3. **Keycloak-side misconfiguration** (role assigned as a client role instead of realm role, so `realm_access.roles` never carries it) — checked directly via the Keycloak admin API (`GET /admin/realms/ecom/users/{id}/role-mappings/realm`): `CAN_TRACE` **is** correctly assigned as a realm role, `clientRole: false`.
4. **Gateway not picking up the role** — checked the gateway's own `AUDIT` log for the `LOGIN` event (`KeycloakOidcUserService`, see ADR-0032): `authorities=[ROLE_CUSTOMER, ..., ROLE_CAN_TRACE, ...]` — confirmed the gateway's session correctly held `CAN_TRACE` at the moment of login.

That narrowed the gap to `user-service` itself. `UserController#me` computes `roles` freshly from the JWT's `realm_access.roles` on every call (correct), then calls `UserService#getOrCreateByKeycloakSubject(...)`, passing that freshly-computed set — but the old implementation only ever wrote `user.setRoles(roles)` inside the `orElseGet` (create) branch. Both lookup-hit branches (`findByKeycloakSubjectId` direct hit, and the `findByEmail` legacy-row-migration fallback documented above this method) returned the existing DB row completely untouched aside from bookkeeping fields (`updatedAt`, `keycloakSubjectId`, `name`) — `roles` was never among them. The DB row's `roles` collection was therefore a permanent snapshot of whatever it was the first time this method ever ran for that person, regardless of how many times Keycloak's role assignment changed afterward or how many times they re-logged-in.

This directly regresses an explicit requirement captured earlier in this project's live-bug-triage work: *"When user record exists it picks role from keycloak."* The code read the JWT's roles correctly but then discarded them for any returning user.

## Decision

`UserService#getOrCreateByKeycloakSubject` now writes `roles` on every branch, via a shared `syncRoles(User, Set<Role>)` helper: the direct `findByKeycloakSubjectId` hit, the `findByEmail` legacy-migration fallback, and the create path all end up with the DB row's `roles` matching exactly what was just read from the caller's current JWT. Keycloak remains the single source of truth for role assignment — this DB column is now genuinely a cache of it, refreshed on every login, not an independent record that can drift.

No new role-assignment mechanism was introduced — `roles` was already being computed correctly from the JWT on every call (`UserController#me`); this only fixes what happened to that value afterward.

## Regression guard

`UserServiceTest` (new, 5 cases) unit-tests `getOrCreateByKeycloakSubject` against a mocked `UserRepository`, covering every branch:
- an existing user found by subject ID gets a newly-granted role added to their stored roles;
- the `findByEmail` legacy-migration fallback branch also syncs roles, not just identity fields;
- a brand-new user is created with the roles passed in;
- a brand-new user with no roles in the token defaults to `CUSTOMER` (existing behavior, preserved);
- a role **revoked** in Keycloak is correctly removed from the stored row on the next sync (roles are replaced, not merged/unioned) — this is the same fix, verified from the opposite direction.

Live re-verification once `user-service` is redeployed: `GET /user/me` as `ravik775@gmail.com` after a fresh login should now return `"roles": ["CUSTOMER", "CAN_TRACE"]`.

## Consequences

- Positive: closes a real, reported requirement gap — role changes made in Keycloak now actually reach `/user/me` (and therefore the UI's Settings/Roles menu) on the very next login, matching the explicit "picks role from keycloak" requirement from earlier in this project.
- Negative / accepted trade-off: every login now does one extra conditional `UPDATE` when roles have changed (none when they haven't, via the `!user.getRoles().equals(currentRoles)` check in `syncRoles`) — negligible cost at this system's scale (100 users, 20 active, per ADR-0036's capacity planning).
- Follow-up required: none currently open.

## Related

- Related: ADR-0049 (gateway session absolute max-age — a different, now-ruled-out layer of the same "stale role" symptom class; that ADR bounds gateway-session staleness, this one fixes a genuine backend bug that would have kept happening even with ADR-0049 working perfectly), ADR-0025 (JWT/RBAC — Keycloak as the source of truth for role assignment), ADR-0033 (role model this fix operates on)
