# ADR-0024: Idempotency — hybrid client Idempotency-Key with sanitized-payload-hash fallback

**Status**: Accepted
**Date**: 2026-08-13
**Deciders**: Solution/Security Architect

## Context

State-changing endpoints (`POST /orders`, `POST /payments`) must not double-execute on client retry (e.g., a client times out waiting for a response and resends the identical request). The initial instruction was to identify duplicates purely by hashing the sanitized request payload, with no client cooperation required.

## The Real Conflict Found

A pure content-hash approach cannot distinguish "the same request, retried after a network failure" from "the customer genuinely wants to submit this exact same order twice" (e.g., two identical carts checked out moments apart) — both hash identically and would be incorrectly collapsed into one order under a hash-only scheme. This is a real correctness gap, not a hypothetical.

## Options Considered

| Option | Pros | Cons |
|---|---|---|
| Hash-only (as originally specified) | Simplest; zero client cooperation needed; matches the instruction literally | Cannot distinguish retry from intentional duplicate — a real, accepted risk |
| Hybrid: optional client `Idempotency-Key` header, sanitized-payload hash as fallback (**chosen**) | Unambiguous when the client cooperates (a client wanting two identical orders just sends two different keys); still works with zero client cooperation via the hash fallback; matches the industry-standard combined model | Slightly more implementation surface than hash-only |

## Evidence

- The combined key+hash model is the documented best practice: "Reusing a key with different params returns 400, not silent cache hit, following Stripe's model... the request_hash catches client bugs where the same key is reused with a different request body." ([boundedcontext.com / algomaster.io pattern summary, corroborated across multiple sources](https://dev.to/apikumo/idempotency-keys-the-api-pattern-that-saves-you-from-duplicate-payments-and-phantom-records-51b2))
- Deterministic hash-as-key (no client header) is also a recognized, legitimate variant: "Using a deterministic hash instead of a random UUID means the client can reconstruct the key after a crash, without needing to store it separately. This works well for deterministic operations." — this is exactly the fallback path when no client key is supplied.
- Atomicity matters regardless of which identity scheme is used: "A single atomic insert is your safety barrier. Without it, concurrent requests will both pass 'not seen' checks and double-execute" — informs the implementation (a unique DB constraint on the resolved key, not a check-then-insert race).
- TTL guidance: "24 hours for API requests... old enough that retries are impossible, short enough that storage stays bounded" — informs the expiry policy below.

## Decision

Resolve the idempotency identity per request as: **client-supplied `Idempotency-Key` header if present, otherwise SHA-256 hex hash of the request DTO** (already-sanitized by construction — request DTOs carry only business fields, no timestamps/volatile data, so no separate exclusion-list step is needed). Implemented once in `common-lib` (`org.bgm.common.idempotency.PayloadHasher`, deterministic via alphabetically-sorted JSON serialization so field order never affects the hash), consumed per service with its own idempotency-record table (schema-per-service, ADR-0004) — storage is per-service, the hashing algorithm is shared.

Record stored per resolved key: the key itself, the request hash (stored even when a client key was supplied — reusing a key with a different payload is rejected, not silently replayed, per the Stripe model), the serialized response, and an expiry timestamp (24h TTL). A unique DB constraint on the resolved key is the concurrency safety barrier — not an application-level check-then-insert.

## Consequences

- Positive: closes the real correctness gap a hash-only design would have shipped with; zero-cooperation clients are still fully covered by the hash fallback; reusing a key with a mismatched payload is caught, not silently misapplied.
- Negative / accepted trade-off: idempotency records are a new table per service that needs them (order-service, payment-service to start) — small, bounded storage cost with a 24h TTL, not a new infrastructure component.
- Follow-up required: apply the same pattern (already proven in order-service) to payment-service's `POST /payments` when that endpoint is next touched.

## Related

- Related architecture doc: `doc/architecture/04-application-architecture.md`
- ADR-0007 (saga/outbox/idempotency) — that ADR covers **Kafka consumer** idempotency (at-least-once delivery); this ADR covers **REST API request** idempotency (client retries). Related layers, not duplicates — a full request can need both once Phase 3 lands.
