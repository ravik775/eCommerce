# ADR-0047: Password-login gating for Google-provisioned users, and a self-service "Reset Password" entry point

**Status**: Accepted
**Date**: 2026-08-16 15:05 IST
**Deciders**: Solution/Security Architect

## Context

Today, a Google-authenticated user's Keycloak account is auto-provisioned (via the `first broker login` flow) with the `CUSTOMER` role and no password credential — but the account is **enabled**, and nothing tracks or surfaces whether password-based login is meant to be available for that user. The requested flow: a fresh Google-provisioned user should have password login explicitly withheld until an `IAM_ADMIN`/`PLATFORM_ADMIN` deliberately grants it (and sets an initial password), and the UI should expose a "Reset Password" action gated on that state.

## Options Considered (carried over from the design discussion before this ADR)

**Where does the "password login allowed" state live?**

| Option | Trade-off |
|---|---|
| Disable the whole Keycloak account until unlocked | Simplest, but blocks Google login too — contradicts the requirement that Google login keep working immediately for a JIT-provisioned user |
| **A boolean tracked in `user-service`'s own `users` table** (chosen) | Google login is unaffected (Keycloak account stays enabled); the flag is purely this app's own concept, checked and displayed without needing any new Keycloak-admin-scoped credential |

**How does an admin lift the flag and set the initial password?**

| Option | Trade-off |
|---|---|
| **Admin uses Keycloak's own admin console/API to reset the password** (chosen) | Zero new service-account credential — [ADR-0033](ADR-0033-admin-role-restricted-to-iam-operations-admin-split.md) already explicitly descoped exactly this class of "give a service a standing Keycloak-admin credential" decision, for good reason (real threat-model expansion). Re-opening that decision isn't warranted just to save an admin one console visit |
| A new in-app admin screen that calls the Keycloak Admin REST API on the admin's behalf | Requires exactly the standing service-account credential ADR-0033 rejected — deferred, same reasoning as that ADR, not re-litigated here |

**How does the user actually change their own password once enabled?**

| Option | Trade-off |
|---|---|
| A custom password-change form in this app, POSTed to our backend | **Rejected** — this app's own code would have to handle a plaintext password submission and forward it to Keycloak, a real credential-handling liability this architecture has deliberately avoided everywhere else (ADR-0001: "Keycloak owns credentials"; the `User` entity's own header comment: "no password field exists here by design") |
| **Redirect to Keycloak's own Account Console** (`/realms/ecom/account/`), already-authenticated via the existing SSO session (chosen) | Zero new credential-handling surface in this app at all — Keycloak's own account UI does the actual password change; matches the OWASP-standard guidance of not building custom credential-management UI when the IdP already provides a maintained one |

## Decision

1. **`user-service`**: new `password_login_enabled` column (`BOOLEAN NOT NULL DEFAULT FALSE`, Flyway `V2`), defaulting to `false` for every newly-provisioned user (Google-JIT or otherwise — a deliberately simple, uniform default rather than special-casing by provisioning source). Exposed on `GET /user/me`'s response.
2. **New admin-gated endpoint**: `PUT /users/{id}/password-login-enabled`, `@PreAuthorize("hasAnyRole('IAM_ADMIN','PLATFORM_ADMIN')")` — a dedicated endpoint, not folded into the existing general-purpose `PUT /users/{id}` (which has no role gate at all today — a pre-existing gap, out of scope for this ADR, but specifically **not** one to inherit by attaching a new privileged field to it).
3. **UI**: a "Reset Password" item under Settings, always visible but `disabled` (not hidden — the user should be able to see the capability exists and understand *why* it's unavailable) unless `me.passwordLoginEnabled === true`. When enabled and clicked, navigates to Keycloak's Account Console (`/realms/ecom/account/`) rather than any in-app form.
4. **Operational flow for an admin granting access**: (a) admin resets the user's password via Keycloak's admin console (`reset-password` API, `temporary: true` recommended so the user is forced to change it on first use); (b) admin calls the new `PUT /users/{id}/password-login-enabled` endpoint (via this app, using their own already-privileged session — no new credential type introduced) to flip the flag so the UI unlocks the button. Two manual steps, deliberately not unified into one, since unifying them is exactly the standing-service-account-credential expansion this ADR chose not to make.

## Consequences

- Positive: no new credential-handling surface anywhere in this app; Google login for a fresh user is unaffected and immediate; the gating state is simple, inspectable, and owned entirely by data this app already controls.
- Negative / accepted trade-off: granting password-login access is a genuinely two-step manual admin process (Keycloak console + this app's endpoint) rather than one action — accepted explicitly to avoid re-opening ADR-0033's rejected service-account-credential expansion.
- Follow-up required: the pre-existing lack of a role gate on `PUT /users/{id}` (found while implementing this ADR) is a real, separate finding — noted here for visibility, not fixed as part of this change since it's outside this ADR's scope; worth its own follow-up.

## Related

- Amends/extends: ADR-0001 (Keycloak owns credentials), ADR-0033 (the service-account-credential decision this ADR deliberately does not reopen)
- Related: ADR-0025 (RBAC — the `IAM_ADMIN`/`PLATFORM_ADMIN` gate this ADR's new endpoint uses)

## 2026-08-16 15:12 IST update — reversed: the `password_login_enabled` flag is dropped, replaced by an always-shown link

**What changed**: point 1's `user-service` column, point 2's admin-gated endpoint, and point 4's two-step operational flow above are **reversed** — none of that gets built. Only point 3 survives, simplified: "Reset Password" is now **always shown, never disabled**, with no backend state behind it at all. The code that had already been written for the original Decision (Flyway `V2__password_login_enabled.sql`, a `passwordLoginEnabled` field on the `User` entity, the corresponding `UserResponse` field, and a `PUT /users/{id}/password-login-enabled` endpoint) was reverted in the same change that added this update — nothing half-built was left behind.

**Why**: a direct read of Keycloak's own source (`PasswordCredentialProvider.isValid()`, `services/src/main/java/org/keycloak/credential/PasswordCredentialProvider.java`, `main` branch) confirmed that Keycloak *itself* already answers "does this user have a password credential" — `getPassword(realm, user) == null` — and this is exactly the check that determines whether a password-login attempt can ever succeed, with zero involvement from this application. The `password_login_enabled` column this ADR originally proposed would have been a **second, independent source of truth for a fact Keycloak already owns**, with no mechanism keeping the two in sync — concretely: if a user set a password themselves via Keycloak's Account Console (self-service, always available, see below), our own column would still read `false`, incorrectly showing the Reset Password button as disabled for an account that can actually already log in with a password. Storing a fact we don't control, that can silently drift from the source of truth, is a straightforward duplication smell — the requirement (prefer simplicity, no duplication) resolved directly against the original design once this was traced to its root cause rather than assumed.

**The trade-off this reversal accepts, explicitly**: the original Decision's point 4 (two-step admin-gated flow) was an attempt to satisfy an explicit requirement — a fresh Google-provisioned user's password login stays withheld until an admin deliberately grants it. That requirement is **not technically enforced** by this reversed design. Keycloak's Account Console already lets any authenticated user self-service a password credential at any time, admin action or not — closing that would require restricting/customizing Keycloak's own self-service credential UI (a custom theme or authentication-flow change), which is real additional infrastructure this reversal deliberately does not build, consistent with this project's repeated "don't add infrastructure before the need is real" pattern (ADR-0002, ADR-0013, ADR-0014, ADR-0015, ADR-0021, ADR-0022, ADR-0034). The admin-driven "grant password login" action still exists and is still the *recommended* path (`execute-actions-email` with `UPDATE_PASSWORD`, entirely via Keycloak's own admin console/API — no code here) — it's a process convention now, not a technical gate. If enforcing this as a hard technical control ever becomes a real requirement (not a hypothetical one), the correct next step is a Keycloak authentication-flow/theme customization scoped as its own ADR, not resurrecting the dropped `user-service` column.

**Verification before this change was committed**: confirmed no other in-flight fix depended on the reverted files — `git status` showed exactly the 4 files touched by the original (now-reverted) implementation and nothing else; the full reactor test suite was re-run after reverting to confirm the codebase is still green.
