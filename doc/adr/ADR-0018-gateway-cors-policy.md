# ADR-0018: CORS policy — explicit origin allow-list at the API Gateway

**Status**: Accepted
**Date**: 2026-08-13
**Deciders**: Solution/Security Architect

## Context

The UI (ADR-0012) is a separately-served SPA calling the API Gateway from a different origin. Cross-Origin Resource Sharing must be configured explicitly — an omission here (or a wildcard `*` origin, a common shortcut) is a well-known real vulnerability class, not a design trade-off to weigh.

## Decision

The gateway (ADR-0005) configures CORS with an **explicit origin allow-list** (the UI's known origin(s) per environment — local dev, Compose, K8s), not a wildcard. Allowed methods and headers are scoped to what the UI actually needs (`GET, POST, PUT`, `Authorization`/`Content-Type` headers); credentials mode matches whatever token-transport approach Phase 4/8 finalize (e.g., `Authorization: Bearer` header, not cookies, keeps this simpler and avoids needing `credentials: include` CORS complexity).

## Consequences

- Positive: closes a common, easy-to-miss real vulnerability at effectively no cost; scopes exactly to the UI's actual origin per environment.
- Negative / accepted trade-off: the allow-list must be updated per environment (local/Compose/K8s UI origin differs) — a small, expected maintenance item, not a design flaw.
- Follow-up required: finalize the exact allow-listed origins per environment when Phase 5 (Compose) and Phase 6 (K8s) fix the UI's actual serving domain/port.

## Related

- ADR-0005 (API Gateway boundary), ADR-0012 (UI stack)
