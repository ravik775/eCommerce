# ADR-0012: UI stack — minimal React + Vite SPA, Keycloak-hosted login only

**Status**: Accepted
**Date**: 2026-08-12
**Deciders**: Solution/Security Architect

## Context

Requirement #6: a simple UI for users to log in and check out, kept intentionally thin per this project's "prefer simplicity" principle and the earlier scope decision that automated UI testing is out of scope (manual functional verification only, `doc/architecture/10-development-testing-deployment.md`).

## Options Considered

| Option | Pros | Cons |
|---|---|---|
| Server-rendered Thymeleaf, served from api-gateway or a dedicated service | No separate frontend build/toolchain; simplest possible deploy (one more Spring Boot module) | Couples UI rendering to the backend stack; harder to keep "no business logic client-side" clean; less representative of how a real-world checkout UI is actually built today |
| Full-featured frontend framework (Angular, Next.js with SSR) | Scales to a much bigger product surface | Substantial overkill for a login + browse + checkout flow — directly contradicts "prefer simplicity," adds a build/runtime footprint far beyond what 3 pages need |
| Minimal React + Vite SPA, static-built, served as its own lightweight container | Small, fast dev/build loop (Vite), React is the most widely adopted UI library so patterns/documentation/hiring are not a constraint, builds to static assets servable by any lightweight web server — no server-side rendering complexity | Still a second toolchain (Node/npm) alongside the Java stack — accepted, unavoidable for any real browser UI |

## Decision

A minimal **React + Vite** single-page app with three views: Login (redirect to Keycloak, ADR-0001), Catalog browse, Checkout. No client-side business logic — every action calls the API Gateway, which enforces auth/routing per ADR-0005. Built to static assets, served by a lightweight static file server in its own container (Phase 5 onward); no automated UI test suite (prior scope decision) — verified by manual walkthrough per the Phase 8 Definition of Done.

## Consequences

- Positive: smallest reasonable toolchain for a real browser-based login/checkout flow; no duplicated business logic between UI and backend; consistent with every other "add exactly what's needed, nothing more" decision in this document set.
- Negative / accepted trade-off: introduces Node/npm as a second build toolchain in the repo, alongside Maven — unavoidable for any actual browser UI, scoped to the `ui/` directory only.
- Follow-up required: confirm Keycloak's redirect URI configuration for the UI's origin when Phase 4 (Keycloak) and Phase 8 (UI) are both implemented.

## Related

- Related architecture doc: `doc/architecture/13-ui-architecture.md`
- ADR-0001 (Keycloak/OIDC login this UI redirects to)
