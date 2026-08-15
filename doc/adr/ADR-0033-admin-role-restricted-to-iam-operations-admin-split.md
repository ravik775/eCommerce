# ADR-0033: Split ADMIN into IAM-only ADMIN + operational CATALOG_ADMIN/INVENTORY_ADMIN — amends ADR-0025, ADR-0028, ADR-0030

**Status**: Accepted
**Date**: 2026-08-15
**Deciders**: Solution/Security Architect

## Context

Explicit requirement: `ADMIN` should be able to **only** assign/revoke roles and permissions for other users — it should carry **no** operational privilege of its own (no catalog mutation, no inventory restock, no cross-provider ownership bypass). This is the opposite of `ADMIN`'s role in every prior ADR in this project (ADR-0025, ADR-0028, ADR-0030), which all treat `ADMIN` as an operational super-user.

An unused role already exists in the realm, `SUPER_ADMIN` ("Can manage users/roles in addition to ADMIN capabilities"), which was evidently anticipated for something like this. Two implementation paths were presented:

- **Repurpose `SUPER_ADMIN`** as the role-management-only gate, leaving today's `ADMIN` and every existing `@PreAuthorize`/gateway check untouched — smallest possible change.
- **Repurpose `ADMIN`** to match the requirement's literal wording, introducing a new role for today's operational capability and updating every existing check that currently accepts `ADMIN`.

The literal-wording option was explicitly chosen (over the recommended smaller-blast-radius option), so this ADR documents that path.

## Decision

Six-role model, replacing the prior two-role (`ADMIN`/`SUPER_ADMIN` as a strict superset) model — split further than the original proposal, into per-domain operational roles rather than one combined operational role:

- **`CUSTOMER`** — unchanged: browse catalog, place/cancel/return orders.
- **`PROVIDER`** — unchanged: manage own products (ADR-0030), no admin/customer capability implied.
- **`CATALOG_ADMIN`** (new) — exactly what `ADMIN` did for the catalog before this ADR: product mutation across *any* product (not ownership-scoped, unlike `PROVIDER`), the ownership-bypass path in `ProductService`. No inventory privilege, no user/role-management privilege.
- **`INVENTORY_ADMIN`** (new) — exactly what `ADMIN` did for inventory before this ADR: restock across any product. No catalog privilege, no user/role-management privilege.
- **`ADMIN`** (redefined) — reserved for the ability to assign/revoke realm roles for other users. Carries **no** operational privilege — an `ADMIN`-only session cannot mutate the catalog, restock inventory, or bypass product ownership. **Not yet backed by a self-service endpoint** — see the explicit descoping decision below.
- **`SUPER_ADMIN`** — the union: `CATALOG_ADMIN` + `INVENTORY_ADMIN` + `ADMIN`'s capabilities combined, implemented as a genuine **Keycloak composite role** (not re-derived per check) — a `SUPER_ADMIN` JWT's `realm_access.roles` already contains all three via Keycloak's own composite-role expansion, so no `@PreAuthorize`/gateway check needs to list `SUPER_ADMIN` alongside the specific role it means; it only ever names the one role, and the union happens once, at the IAM layer. A true super-user, for break-glass/test convenience, not a distinct privilege of its own.

Every existing `hasRole('ADMIN')` / `hasAnyRole('ADMIN', 'PROVIDER')` / gateway `RequireRole=ADMIN,PROVIDER` check that gates a **catalog** operation is changed to accept `CATALOG_ADMIN` (only); every one gating an **inventory** operation is changed to accept `INVENTORY_ADMIN` (only) — **neither** accepts plain `ADMIN` anymore, and neither needs to separately list `SUPER_ADMIN` (composite expansion covers it). Splitting further than one combined operational role means a catalog-only operator and an inventory-only operator are now two genuinely different, independently assignable privileges — not a package deal.

**Explicit descoping decision — no role-assignment endpoint is built by this ADR.** The original proposal called for a new `user-service` endpoint backed by a Keycloak Admin REST API service-account client. On review, that's a materially larger security decision than "split a role" — it means adding a standing credential with the power to create/modify/delete any user or role in the realm to a live, network-reachable service. That deserves its own threat model, not to ship as a footnote inside this ADR. **Role assignment stays exactly as it is today: a human action via the Keycloak admin console or realm-config edit, out-of-band from the running application.** `ADMIN`'s "only assigns roles" property is therefore currently satisfied by *what it does NOT grant* (zero operational access) rather than by a feature it actively powers — `ADMIN` exists and is reserved for this purpose, but there is nothing for an `ADMIN`-only session to actually click today. This is intentional, not an oversight: consistent with this project's repeated "documented simplification over speculative infrastructure" pattern (ADR-0027, ADR-0030).

## Consequences

- Positive: `ADMIN` genuinely carries zero operational blast radius — an `ADMIN` credential leaking cannot be used to touch the catalog or inventory. `SUPER_ADMIN`'s use of a real Keycloak composite role (not manual per-check duplication) closes a correctness gap found during review: a manually-duplicated `SUPER_ADMIN` check silently stops being a super-user the day someone adds a new endpoint and forgets to list it.
- Negative / accepted trade-off: every operational `@PreAuthorize` check, every gateway route's `RequireRole`, and the `isAdmin(jwt)`-style ownership-bypass helper across `catalog-service` and `inventory-service` all had to change in this one pass — larger blast radius than the recommended alternative, accepted because it was the explicitly chosen path. A `CATALOG_ADMIN`-only caller whose product-creation request includes an initial quantity will have that stock-seeding sub-step silently fail (logged, not fatal — the product itself still gets created) since their token no longer carries `INVENTORY_ADMIN` — a direct, intended consequence of separating the two privileges, not a bug, but worth calling out since it changes existing behavior for admin-created listings.
- Follow-up required: `admin1`'s test-account role assignment was migrated to hold every role directly (not `SUPER_ADMIN` alone) so existing verification flows keep working. If/when a real self-service role-assignment need arises, build it as its own ADR with an explicit threat model for the new Keycloak-admin-scoped credential, rather than folding it into a future unrelated change.

## Related

- Amends ADR-0025 (JWT/RBAC): the "annotate admin-only endpoints with `@PreAuthorize`" example now names `CATALOG_ADMIN`/`INVENTORY_ADMIN` per domain, not `ADMIN`.
- Amends ADR-0028 (gateway role gate): `RequireRole=ADMIN` on the catalog route now reads `CATALOG_ADMIN,PROVIDER`; the inventory route reads `INVENTORY_ADMIN,PROVIDER` — `SUPER_ADMIN` not listed, covered by Keycloak composite-role expansion.
- Amends ADR-0030 (Provider role): its "ADMIN or PROVIDER" gate becomes domain-specific per the above, `PROVIDER` unaffected.
- `keycloak/ecom-realm.json`, `catalog-service/.../ProductController.java` + `ProductService.java`, `inventory-service/.../InventoryController.java`, `k8s/base/configmap-gateway-routes.yaml`. No `user-service` changes — role-assignment endpoint explicitly descoped, see Decision.
