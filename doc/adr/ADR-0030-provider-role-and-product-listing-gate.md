# ADR-0030: Provider role for self-service product listings, with a DRAFT/LISTED gate — amends ADR-0025 and ADR-0028's "ADMIN-only" catalog mutation policy

**Status**: Accepted
**Date**: 2026-08-15
**Deciders**: Solution/Security Architect

## Context

Phase 8 (`doc/architecture/07-migration-planning.md`) needed a way for a non-admin user ("provider"/vendor) to list their own products for sale, with initial stock, without every listing requiring an administrator to create it on the provider's behalf. Two existing ADRs directly constrain this:

- **ADR-0025** ("Decision 2 — RBAC enforcement") states catalog-service's product mutations are gated `@PreAuthorize("hasRole('ADMIN')")`, and its own Follow-up section explicitly names this as the concrete example of "annotate the actual admin-only endpoints."
- **ADR-0028** ("Gateway-level admin gate") states the gateway's `RequireRoleGatewayFilterFactory` rejects any request to `POST/PUT/DELETE /catalog/**` lacking `ROLE_ADMIN`, live-verified with that exact policy.

Both were correct decisions for the problem they solved (closing an RBAC gap that had no role distinctions yet to make). Introducing a second privileged-but-not-admin role changes the shape of that policy at both layers, so both ADRs need an explicit amendment rather than a silent code change that contradicts what they document.

A second, related question the requirement raised: should a provider's listing require approval before customers can see it? The full request that prompted this ADR described a complete vendor-onboarding-and-approval pipeline (vendor submits Name/address/email → an "onboarding" role approves the vendor account; provider submits a product → an "inventory" role approves the listing; event-driven via a DB-backed queue and a message bus). That was evaluated against real 2026 multi-vendor marketplace practice and scoped down — see Evidence and Decision 2 below.

## Options Considered — role model

| Option | Pros | Cons |
|---|---|---|
| Keep ADMIN-only; admins create every provider's listings on their behalf | Zero RBAC/gateway change | Doesn't scale past a handful of providers; admin becomes a bottleneck for every new listing — the actual requirement this ADR exists to solve |
| New `PROVIDER` role, additive to the existing `ADMIN` gate at both layers (gateway `RequireRole=ADMIN,PROVIDER`, service-layer `hasAnyRole('ADMIN','PROVIDER')` + ownership check) | Matches how every real multi-vendor marketplace splits authority: platform admin vs. vendor, vendor owns only their own inventory | A second role to reason about; ownership enforcement must live in the service layer since the gateway filter only checks role, not resource ownership (documented split already established by ADR-0028's own "additive to, not a replacement for" language) |
| Full vendor-onboarding-approval + product-approval pipeline with an event-driven queue (the originally requested design) | Closest to a production-grade marketplace (KYC-style vendor vetting, moderated listings) | Substantial new scope: a vendor-registration workflow, a second privileged role ("onboarding"/"inventory" approver), a DB-backed queue, message-bus events for state transitions — none of which this system has any other precedent for (no other workflow here uses an approval queue), and no named requirement forces it yet |

**Chosen**: the middle option — a `PROVIDER` role additive to the existing `ADMIN` gate, ownership-scoped in the service layer.

## Options Considered — listing visibility gate

| Option | Pros | Cons |
|---|---|---|
| No gate: a provider's product is immediately searchable/purchasable on creation | Simplest — no new field, no new endpoint | Zero moderation-equivalent control at all; a mis-priced or incomplete listing goes live instantly with no chance to review before a customer can buy it |
| Full approval workflow: a separate "inventory" role must approve each listing before it's visible (the original request) | Matches a fully moderated marketplace | Requires a new role, a pending-approval queue/dashboard, and (per the original request) event-driven infrastructure (DB queue + message bus) with no other precedent in this codebase — the kind of "infrastructure before a concrete enforcement target names it" this project has explicitly rejected before (ADR-0027's deferred payment gateway, ADR-0028's rejected speculative tenant claim) |
| `DRAFT`/`LISTED` status field: provider-created products start `DRAFT` (invisible to browse/search), provider (or admin) explicitly publishes to `LISTED` — no separate approval role, no queue | One real gate between creation and visibility, self-service (no bottleneck), zero new infrastructure — a single enum column and one endpoint | Not a moderation control in the "someone else reviews it" sense — a provider can immediately publish their own draft. Accepted as the deliberate scope line for this pass. |

**Chosen**: `DRAFT`/`LISTED`, per explicit user direction after evaluating the full pipeline against this project's established simplification pattern (see Evidence).

## Evidence

- Multi-vendor marketplace architecture research (2026, via live web search — see this session's transcript): "vendors own their own products, inventory, and fulfillment processes, while the marketplace owner controls the storefront and checkout" — confirms provider-owns-their-listing, platform-owns-checkout is the standard split, matching the `providerId`-scoped ownership model chosen here rather than a shared/undifferentiated catalog.
- Same research on moderation: "[e]stablishing minimum quality standards for product listings... and creating clear escalation paths for dispute resolution are essential practices" for *mature* marketplaces — notably framed as a maturity target, not a day-one requirement, supporting a deliberately incomplete-but-honest gate (`DRAFT`/`LISTED`) over either "no gate at all" or building the full moderation pipeline immediately.
- This project's own precedent, cited directly by the user in accepting this scope: the `CUSTOMER`/`ADMIN`/`SUPER_ADMIN` role model itself has no self-service request/approval flow (roles are granted manually via the Keycloak realm import, ADR-0025/ADR-0028's live-verification sections), and checkout's payment step is a simulated processor, not a real integration (ADR-0027). Both are documented, intentional simplifications rather than gaps nobody noticed — `DRAFT`/`LISTED` without a separate approval role follows the identical pattern, not a new one.

## Decision

1. **RBAC**: add a `PROVIDER` realm role (Keycloak). `catalog-service`'s `POST/PUT/DELETE /products*` and `inventory-service`'s `POST /inventory/add` change from `hasRole('ADMIN')` to `hasAnyRole('ADMIN','PROVIDER')`; the gateway's matching routes change from `RequireRole=ADMIN` to `RequireRole=ADMIN,PROVIDER` (`RequireRoleGatewayFilterFactory` extended to accept a comma-separated any-of list, the filter's first use case needing more than one role). Ownership (a `PROVIDER` may only modify/publish *their own* products, identified by `Product.providerId` = the caller's Keycloak subject) is enforced in `ProductService`, not the gateway — same "gateway is coarse pre-check, service layer is authoritative" split ADR-0028 already established.
2. **Listing visibility**: `Product` gains a `status` (`DRAFT`/`LISTED`) column. `ADMIN`-created products default to `LISTED` (unchanged behavior for the existing catalog — admin was already trusted, no new gate for them). `PROVIDER`-created products default to `DRAFT` and require an explicit `PUT /products/{id}/publish` call (self-service, ownership-checked) to become `LISTED`. Browse/search (`GET /products/search`) only ever returns `LISTED` + `active` products.
3. **Explicitly not built**: vendor self-registration (Name/address/email submission), a vendor-approval role/workflow, a separate listing-approval role, and any event-driven queue (DB-backed or message-bus) for either. `PROVIDER` role grant stays a manual Keycloak action, identical to how `ADMIN`/`SUPER_ADMIN` already work.

## Consequences

- Positive: closes the real scaling gap (admin-as-bottleneck for every new listing) with the minimum mechanism that still has one real, self-service gate between "created" and "visible to customers" — not zero gate, not a full moderation pipeline nothing else in this codebase has precedent for.
- Negative / accepted trade-off: no actual moderation (a provider approves their own listing by definition) and no vendor vetting at all — a bad-faith account with the `PROVIDER` role can list anything. Accepted because role grants are already a trusted, manual, out-of-band action in this system (same trust model `ADMIN` already has), not a self-service signup a stranger could obtain.
- Follow-up required: if real multi-party vendor trust ever becomes a requirement (vendors who are *not* implicitly trusted the way a manually-role-granted account is), this ADR's trade-off no longer holds and the full pipeline from the "Options Considered" table — vendor registration, an approval role, and the queue/event infrastructure to back it — should be revisited against the concrete trust requirement that names it, not built speculatively now.

## Related

- Amends ADR-0025 (Decision 2 — RBAC enforcement): the "ADMIN-only" example given in that ADR's Follow-up section is superseded for catalog mutations specifically; ADR-0025's core decisions (JWT format, `JwtAuthenticationConverter` mapping mechanism) are unaffected.
- Amends ADR-0028 (gateway-level admin gate): the specific `RequireRole=ADMIN` policy on `/catalog/**` mutations is superseded by `RequireRole=ADMIN,PROVIDER`; ADR-0028's core decision (role-only, no tenant dimension) is unaffected and still applies — `PROVIDER` is a role, not a tenant claim.
- ADR-0027 (payment gateway integration deferred) and ADR-0028 (rejected speculative tenant claim) — both cited as this project's existing precedent for "don't build infrastructure without a concrete enforcement target," applied here to reject the event-driven approval queue.
- `api-gateway/src/main/java/org/bgm/apigateway/config/RequireRoleGatewayFilterFactory.java`, `catalog-service/src/main/java/org/bgm/catalogservice/service/ProductService.java`, `catalog-service/src/main/java/org/bgm/catalogservice/model/ProductStatus.java`.
