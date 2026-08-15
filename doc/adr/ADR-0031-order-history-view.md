# ADR-0031: Add an Order History view — amends ADR-0012's three-view UI scope

**Status**: Accepted
**Date**: 2026-08-15
**Deciders**: Solution/Security Architect

## Context

ADR-0012 scoped the UI to exactly three views — "Login, Catalog browse, Checkout" — deliberately excluding everything else per this project's "add exactly what's needed, nothing more" principle, at a time when the checkout flow itself hadn't been built yet.

With checkout now live-verified end-to-end (order creation returns a real order ID synchronously, per `OrderController.createOrder`), a customer has no way to see what they've ordered or its status after leaving the page — there is no fourth view, and no link from checkout back to any order list. This is a real gap, not a hypothetical one: `order-service` already exposes `GET /orders/customer/{customerId}` (used internally, never surfaced), so the backend capability already exists and sits unused.

## Options Considered

| Option | Pros | Cons |
|---|---|---|
| Leave as-is; user must remember the order ID shown at checkout | Zero new code, stays inside ADR-0012's original three views | A user who navigates away loses all access to their own order status — not a checkout flow a real customer could use, and directly contradicts this project's own "verified by manual walkthrough per the Phase 8 Definition of Done" standard (ADR-0012), since no walkthrough of "check my order status" is possible today |
| Full order-detail page (line items, shipping, cancel/return actions) | Closest to a real e-commerce order-history page | `updateStatus` (`PUT /orders/{id}/{action}`, cancel/return) and per-order line-item display are real scope beyond what's needed to close the actual gap (seeing status); matches this project's repeated pattern of rejecting speculative scope (ADR-0027, ADR-0030) |
| Minimal Order History list: order ID, status, payment status, created time — fetched from the existing `GET /orders/customer/{customerId}` endpoint, refreshed after every successful checkout | Closes the actual gap (a customer can see what they ordered and its status) with zero backend changes — the endpoint already exists; matches ADR-0012's "no client-side business logic, every action calls the gateway" constraint exactly | No cancel/return actions, no line-item drill-down — accepted as the deliberate scope line, same class of simplification as DRAFT/LISTED (ADR-0030) |

**Chosen**: the minimal Order History list.

## Evidence

- `order-service`'s `GET /orders/customer/{customerId}` (`OrderController.java:50`) already returns `id`, `orderStatus`, `paymentStatus`, `orderCreatedOn` per order — confirms the "no backend change" cost estimate rather than assuming it.
- This project's own established pattern (ADR-0027, ADR-0030): ship the minimum real mechanism that closes a genuine gap, defer richer capability (cancel/return actions, line items) until a concrete requirement names it — applied identically here.

## Decision

Add a fourth UI view, "My Orders": on login and after every successful checkout, fetch `GET /order/customer/{me.id}` (gateway's existing `order-service` route, no new route needed — `RewritePath=/order(?<remaining>.*), /orders${remaining}` already covers this sub-path) and render each order's ID, status, payment status, and created time. No cancel/return UI, no per-order line-item view.

## Consequences

- Positive: closes a real usability gap with a single new fetch + render function, zero backend/gateway changes, and zero new authorization surface (`GET /orders/customer/{customerId}` has no additional RBAC beyond the existing authenticated-session requirement all `/order/**` routes already carry).
- Negative / accepted trade-off: a customer still can't cancel/return an order or see line items from this view — must be added later against a concrete requirement, not spawned speculatively now.
- Follow-up required: none identified; `updateStatus`'s cancel/return actions remain unexposed in the UI until a requirement names them.

## Related

- Amends ADR-0012 (UI stack): the "three views" decision is superseded to four; ADR-0012's core stack decision (no client framework in the actual implementation — see Follow-up below) is unaffected.
- Follow-up note (pre-existing, not introduced by this ADR): the deployed UI is a vanilla-JS static app served by nginx, not the React+Vite SPA ADR-0012 decided on — a drift between that ADR and `ui/` as actually built, out of scope for this ADR to resolve but flagged here per TOGAF governance practice (a decision record should not silently diverge from what's running).
- `order-service/src/main/java/org/bgm/orderservice/controller/OrderController.java`, `ui/src/app.js`, `ui/src/index.html`.
