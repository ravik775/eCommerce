# ADR-0015: Kafka event schemas — JSON with documented schema, no schema registry

**Status**: Accepted
**Date**: 2026-08-13
**Deciders**: Solution/Security Architect

## Context

A schema registry (Avro/Protobuf + Confluent Schema Registry or Karapace) enforces backward/forward-compatible event evolution at publish time — a real safeguard for the "real-world event-driven system" bar. Weighed against adding a 12th+ infrastructure component for a platform with 7 well-defined event types (ADR-0003).

## Options Considered

| Option | New infrastructure? | Guarantee |
|---|---|---|
| JSON events, schema documented in `doc/architecture`, enforced by code review + consumer tests | None | Weaker: nothing stops a producer from silently breaking a consumer's expectations except test coverage |
| Schema registry (Karapace, open-source) + Avro/Protobuf | Yes — a new stateful service, plus every producer/consumer switches serialization format | Strong: incompatible schema changes rejected at publish time |

## Decision

Kafka events stay plain JSON — but are **programmatically validated** against a checked-in JSON Schema, not just documented and hoped-for. `common-lib` provides `org.bgm.common.event.schema.EventSchemaValidator`, backed by the official `com.networknt:json-schema-validator` library, and one JSON Schema file per event type under `common-lib/src/main/resources/schemas/`. Producers validate a payload before writing it to the outbox table (ADR-0007); consumers validate again on receipt (defense in depth against schema drift between independently-built service jars). This is the concrete implementation of "schema enforced by tests," not a replacement for it — consumer-side integration tests (Phase 3 DoD) still assert expected shape at the business-logic level. No schema registry service is introduced; the schema files themselves, versioned in this repo and shared via one Maven dependency, are the "registry."

**Verified**: `EventSchemaValidatorTest` (common-lib) proves this actually works — a valid `order-created` payload passes; the exact breaking-change scenario discussed when this ADR was decided (`orderId` renamed to `order_id`) is rejected with a real validation error; a wrong-typed field and a missing required array are both rejected. All 8 event schemas (7 from ADR-0003 plus `inventory-reservation-failed` from ADR-0007's saga compensation path) load and validate a minimal correct payload. 5/5 tests passing as of this writing.

## Consequences

- Positive: JSON keeps producers/consumers simple; schema violations are caught by a shared library call, not solely by whether someone remembered to write a test for a specific field; zero new infrastructure component — the "registry" is a jar dependency, not a service.
- Negative / accepted trade-off: this still doesn't provide registry-style *cross-service, pre-deploy* compatibility checking (a producer and consumer on different schema-file versions, if `common-lib` isn't bumped in lockstep, could still drift) — accepted for the reason in the original decision (single repo, single CI run, all services rebuilt together). Revisit if services are ever split into independently-deployed repositories.
- Follow-up required: wire `EventSchemaValidator.validate(...)` into the actual outbox-write and consumer-receive code paths when Phase 3 implements them — the validator exists and is tested, but Phase 3's producers/consumers don't exist yet to call it.

## Related

- ADR-0003 (Kafka + RabbitMQ), ADR-0007 (saga/outbox — events this schema decision applies to)
