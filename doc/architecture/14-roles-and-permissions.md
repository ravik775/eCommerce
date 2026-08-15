# Roles and Permissions

Single reference for the realm's roles and what each one actually grants. The authoritative decision records are ADR-0025, ADR-0028, ADR-0030, and ADR-0033 (roles here reflect ADR-0033's redefinition, which supersedes the earlier ADRs' IAM_ADMIN semantics — see each role's "History" note below).

## Role table

| Role | Grants | Does NOT grant | Composite? |
|---|---|---|---|
| `CUSTOMER` | Browse catalog, place/cancel/return orders | Any catalog/inventory/user-management action | No |
| `PROVIDER` | Create/edit/deactivate/publish **own** products only (ownership-checked in `ProductService`); add stock for own products | Touching another provider's products, any user-management action | No |
| `CATALOG_ADMIN` | Create/edit/deactivate/publish **any** product, not ownership-scoped | Inventory restock, user/role management | No |
| `INVENTORY_ADMIN` | Restock **any** product's inventory | Catalog mutation, user/role management | No |
| `IAM_ADMIN` | Reserved for assigning/revoking roles for other users | **Nothing operational** — cannot touch catalog or inventory at all | No |
| `PLATFORM_ADMIN` | Everything `CATALOG_ADMIN` + `INVENTORY_ADMIN` + `IAM_ADMIN` grant, combined | — | **Yes** — a real Keycloak composite role; its JWT's `realm_access.roles` contains all three automatically |
| `CAN_TRACE` | Can enable the Settings → force verbose tracing toggle (ADR-0032, not yet implemented) | Nothing else | No |

**History**: prior to ADR-0033 (2026-08-15), `IAM_ADMIN` was the operational super-role (what `CATALOG_ADMIN`/`INVENTORY_ADMIN` are now) and `PLATFORM_ADMIN` additionally had user-management on top. ADR-0033 flipped this: `IAM_ADMIN` was narrowed to user-management only, and the operational capability was split into two independent roles. If you find code or docs elsewhere still describing `IAM_ADMIN` as operational, that's now stale — flag it.

## Important caveat: `IAM_ADMIN` currently grants nothing to use

ADR-0033 explicitly descoped building a role-assignment endpoint (it would require a standing Keycloak-admin-scoped credential in `user-service`, judged too large a security decision to fold into that ADR). **Role assignment today is still a manual action** — editing `keycloak/ecom-realm.json.template` or using the Keycloak admin console directly, exactly as it was before this change. `IAM_ADMIN` exists and is reserved for this purpose, but there is currently no in-app feature an `IAM_ADMIN`-only session can use. This is intentional, not a bug — see ADR-0033's Decision section.

## Enforcement layers (defense in depth, same pattern everywhere)

1. **Gateway edge** (`k8s/base/configmap-gateway-routes.yaml`, `RequireRoleGatewayFilterFactory`) — coarse pre-check, rejects before the request reaches a backend at all.
2. **Service `@PreAuthorize`** (`catalog-service`/`inventory-service` controllers) — authoritative role check.
3. **Ownership check** (`ProductService.requireOwnerOrAdmin`) — a `PROVIDER` can only touch products where `providerId` matches their own identity; `CATALOG_ADMIN`/`PLATFORM_ADMIN` bypass this.

None of these three layers duplicate `PLATFORM_ADMIN` explicitly — it's a Keycloak composite role, so its JWT already carries `CATALOG_ADMIN`/`INVENTORY_ADMIN`/`IAM_ADMIN`, and every check only ever names the one role it actually means.

## Identity claim caveat (found live, 2026-08-15)

This Keycloak deployment's issued access tokens do not carry the standard OIDC `sub` claim (confirmed on tokens from both the `master` and `ecom` realms, regardless of scope requested — a genuine characteristic of this instance, not a request/config mistake on the caller's side). Every place in this codebase that needs a stable per-user identity key uses `preferred_username` instead of `jwt.getSubject()` for this reason (`catalog-service`'s `ProductController.callerId()`, `user-service`'s `UserController.me()`). If you add new code that needs to identify "who is this JWT for," use `preferred_username`, not `getSubject()` — the latter is silently `null` in this environment.

## Test users (`keycloak/ecom-realm.json.template`)

| Username | Password | Roles |
|---|---|---|
| `customer1` | `customer1-pass` | `CUSTOMER` |
| `provider1` | `provider1-pass` | `PROVIDER` |
| `admin1` | `admin1-pass` | All seven roles directly (test convenience, not a template for real assignment) |
| `platformadmin1` | `platformadmin1-pass` | `PLATFORM_ADMIN`, `CUSTOMER` (demonstrates composite expansion alone — no direct `CATALOG_ADMIN`/`INVENTORY_ADMIN`/`IAM_ADMIN` grant, those come entirely from the composite role) — on the currently-running cluster this account is still named `superadmin1` (Keycloak's `editUsernameAllowed` is off in this realm, so a live rename wasn't possible; a fresh deployment from the template gets the new username correctly) |

## Known interaction to be aware of

A `CATALOG_ADMIN`-only caller creating a product with an initial quantity will have the stock-seeding sub-step silently fail (logged as a warning, not fatal — the product itself is still created) because their token doesn't carry `INVENTORY_ADMIN`. This is the intended consequence of separating the two privileges, not a bug — see `ProductService.create()`'s comment and ADR-0033's Consequences section.
