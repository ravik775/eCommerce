# ADR-0028: Gateway-level admin gate is role-based only — no tenant dimension

**Status**: Accepted
**Date**: 2026-08-13
**Deciders**: Solution/Security Architect

## Context

Requirement: the API Gateway must reject admin-only requests (e.g. catalog mutation) at the edge, based on the identified user's role, "for that tenant." The initial framing implied a tenant claim was needed alongside role. Before building it, the tenant premise itself was challenged: is this platform's "tenant" the SaaS/customer-data-isolation concept the industry uses that term for, or something lighter — and does building it now add real security value or just surface area?

This system is a **single storefront**: one product catalog, one order flow, one inventory pool, one company. There is no per-business-unit partitioning anywhere in the domain model — `Product`, `Order`, and `Inventory` carry no business-unit/region/org column, and no requirement has named a concrete split that needs one. The user clarified "tenant" was meant as an *internal org/business unit boundary*, not multi-vendor/multi-customer isolation.

## Why the SaaS multi-tenancy philosophy doesn't apply here

Multi-tenant SaaS patterns (Clerk, SuperTokens, Zuplo, AWS Bedrock AgentCore) exist to solve one specific problem: **unrelated customers sharing infrastructure must never see each other's data.** That forces a `tenant_id` onto every domain table, a query-time filter on every repository call, and cryptographic validation of the tenant claim at every hop — because a miss is a cross-customer data leak. None of that precondition holds here: there is exactly one storefront, one dataset, and no untrusted-from-each-other party sharing it. "Business unit" as originally framed is not a data-isolation boundary in this system — it's a potential *authorization* attribute (who may manage what), which is what RBAC already exists to express.

Concretely: a `tenant_id` JWT claim with no `tenant_id` column on any table to check it against authorizes nothing. It would be metadata the system carries but never enforces — exactly the "infrastructure before it's needed" pattern already rejected once for the payment gateway (ADR-0027). Adding it now would mean: a new Keycloak protocol mapper, a new claim threaded through every service, and a decision about what it scopes — all speculative, since no concrete business-unit split has been named.

## Options Considered

| Option | Pros | Cons |
|---|---|---|
| Drop tenant dimension; gateway rejects by ROLE only | Zero new claims/infra; reuses `realm_access.roles`, already issued by Keycloak and already consumed by every backend service's resource server (ADR-0025) | None specific to this system's current shape — the only "loss" is a claim that had nothing to enforce anyway |
| Keep `tenant_id` claim as unenforced metadata for future use | Cheap to add now, avoids a retrofit later if a BU split ever appears | Adds a claim, a protocol mapper, and conceptual surface area that authorizes nothing today — dead weight until (if ever) a real need appears |
| Commit fully: add `tenant_id` to `Product`/`Order`/`Inventory`, enforce row-level filtering now | The claim would mean something immediately | Real scope increase across 3+ services' data models, repositories, and tests, for a requirement that has not been concretely specified (no named business units, no stated data split) |

## Decision

**Role-only gateway rejection.** The gateway maps Keycloak's `realm_access.roles` claim onto the OAuth2 login session (`KeycloakOidcUserService`, mirroring `common-lib`'s existing `KeycloakRealmRoleConverter` used by every backend resource server) and a new `RequireRoleGatewayFilterFactory` gateway filter rejects with 403 any request to an admin-only route lacking the required `ROLE_ADMIN` authority — applied first to catalog's mutating routes (`POST/PUT/DELETE /catalog/**`). No tenant claim is introduced. This is additive to, not a replacement for, each backend service's own `@PreAuthorize` checks (ADR-0025) — a request that reaches a service directly, bypassing the gateway, is still independently rejected.

Tenant/business-unit scoping is explicitly **not built**, not silently dropped — if a concrete business-unit data split is ever named (e.g. "region-scoped admins"), it should be designed against the actual tables it needs to restrict, not bolted onto the JWT in advance of that need.

## Live verification (2026-08-13)

Confirmed working end-to-end via the real Authorization Code + PKCE browser login flow (not a bearer-token shortcut): a `CUSTOMER` session gets 403 from the gateway on `POST /catalog/products` before the request reaches catalog-service; an `ADMIN` session gets 201. Three real bugs surfaced and were fixed during this verification, not assumed correct from code review:

1. Keycloak's confidential `ecommerce-gateway` client mandates PKCE, but Spring's reactive OAuth2 client only auto-attaches PKCE parameters for public clients — login failed with `Missing parameter: code_challenge_method` until an explicit `authorizationRequestResolver` forced `withPkce()` regardless of client type.
2. `KeycloakOidcUserService` originally read `realm_access` from the ID token; this realm's default "roles" client scope does not add that claim to the ID token or `/userinfo` (confirmed by direct inspection), only to the access token — fixed to parse the access token JWT via Nimbus instead.
3. `RequireRoleGatewayFilterFactory`'s shortcut syntax (`RequireRole=ADMIN`) bound to an auto-generated key instead of the `role` field (missing `shortcutFieldOrder()`), so `config.getRole()` was always `null` — every role, including ADMIN, was silently rejected. Isolated via temporary debug logging that showed the correct `ROLE_ADMIN` authority present in the `SecurityContext` while the filter still returned 403, pointing at the filter's own config binding rather than authentication.

None of these three change the ADR's decision (role-only, no tenant) — they're implementation bugs in the mechanism, found only because the verification used the real login flow instead of stopping at "the code looks right."

## Consequences

- Positive: no speculative claim, protocol mapper, or per-service plumbing for a boundary nothing currently enforces; RBAC (role) is the correct, already-built mechanism for "which admin actions are permitted," and the gateway now rejects the same way every backend service already does, just earlier (saves the round-trip, doesn't replace defense-in-depth).
- Negative / accepted trade-off: if a genuine business-unit-scoped admin restriction is needed later, it requires new work then (data model + claim + enforcement) rather than reusing a placeholder built now — accepted because that placeholder would have had no enforcement target to validate it against.
- Follow-up required: if/when a concrete business-unit split is named, revisit this ADR with the actual tables and query patterns that need scoping, rather than generalizing from this decision.

## Related

- Supersedes the tenant-claim scaffolding started and reverted during this session (Keycloak `ecom-realm.json` `tenant_id` protocol mapper, added then removed before realm re-import).
- ADR-0025 (JWT/RBAC, per-service `@PreAuthorize` — the defense-in-depth layer this gateway filter is additive to).
- ADR-0027 (payment gateway deferred — same "don't build infrastructure without a concrete enforcement target" reasoning).
- ADR-0009 (Redis rate limiter — `userKeyResolver` fixed alongside this work to key by authenticated principal instead of client IP).
- `api-gateway/src/main/java/org/bgm/apigateway/config/RequireRoleGatewayFilterFactory.java`, `KeycloakOidcUserService.java`, `GatewayConfig.java`.
