# ADR-0017: OIDC public client hardening — PKCE mandatory for the SPA

**Status**: Accepted
**Date**: 2026-08-13
**Deciders**: Solution/Security Architect

## Context

ADR-0012 commits to a React SPA as a public OAuth2 client (no client secret, since a secret embedded in browser-delivered JS isn't a secret). ADR-0001 didn't specify the authorization flow's hardening for that fact. This was a genuine omission a security review should catch: public clients doing plain Authorization Code flow are vulnerable to authorization-code interception; the fix is well-established, not a design trade-off.

## Decision

The UI's Keycloak login flow (ADR-0001, ADR-0012) uses **OAuth2 Authorization Code with PKCE** (RFC 7636), the current baseline best practice for public/native/SPA clients (IETF OAuth 2.0 Security Best Current Practice, RFC 8252). The Keycloak client registered for the UI is configured as a **public client with PKCE required** (`S256` code challenge method) — Keycloak rejects any authorization request without a valid PKCE challenge for this client.

## Consequences

- Positive: closes a real, well-known attack surface for public OAuth2 clients at effectively no cost — PKCE is supported natively by both Keycloak and standard OIDC client libraries, no extra infrastructure or meaningful complexity.
- Negative / accepted trade-off: none material — this is close to a strict improvement over plain Authorization Code for a public client.
- Follow-up required: configure the Keycloak client's PKCE requirement when Phase 4 (Keycloak) and Phase 8 (UI) are implemented; confirm the UI's OIDC client library generates the code verifier/challenge correctly.

## Related

- ADR-0001 (Keycloak/OIDC), ADR-0012 (UI stack)
