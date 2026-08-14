# 13 — UI Architecture

Full rationale and evidence: ADR-0012.

## Views

| View | Responsibility |
|---|---|
| Login | Redirect to Keycloak-hosted login (Google federated, ADR-0001); no custom password form; handles the OIDC redirect back with the token/session |
| Catalog | Browse products (calls API Gateway `GET /catalog/**`), no client-side pricing/discount logic — displays what the backend returns |
| Checkout | Cart (client-side state only, not persisted server-side until order placement) → place order (`POST` through the gateway to order-service) → shows resulting order status |

## Explicit Non-Goals

- No automated UI test suite (scope decision, `doc/architecture/10-development-testing-deployment.md`) — verified by manual walkthrough only.
- No client-side business logic (pricing, discount calculation, inventory checks) — the UI only renders what the backend APIs return and submits what the user enters; every rule lives in the corresponding backend service (catalog-service, order-service, etc.), per this project's data/service ownership boundaries (ADR-0004).
- No client-side credential handling — login is a redirect to Keycloak; the UI never sees a password.

## Deployment

Built to static assets (`vite build`), served by a lightweight static file server (e.g., nginx or a minimal Node static server) in its own container from Phase 5 (Docker Compose) onward, following the same non-root/health-check conventions as the backend services (`doc/architecture/07-migration-planning.md`, Phase 5 DoD).

## Session Handling

The UI holds the OIDC token/session as issued by Keycloak via the gateway's login flow (ADR-0001, ADR-0005); a page reload must not lose the session (Phase 8 Definition of Done) — implemented via standard browser storage appropriate to the OIDC client type used (public SPA client), not a custom scheme.

## Related

- ADR-0012 (UI stack decision)
- `doc/architecture/02-business-architecture.md` for the business flows this UI exposes
- `doc/architecture/07-migration-planning.md`, Phase 8, for the Definition of Done
