# ADR-0052: Gateway-generated X-Trace-Id, propagated end-to-end including async saga hops

**Status**: Accepted
**Date**: 2026-08-17 05:15 IST
**Deciders**: Solution/Security Architect

## Context

While investigating why a specific correlation/trace ID couldn't be found in Loki, two related gaps surfaced from direct code inspection (not assumption):

1. **The gateway trusted a client-supplied `X-Trace-Id`.** `CorrelationTraceGatewayFilter` and common-lib's `CorrelationTraceFilter` both used a `firstNonBlank(header-value)` pattern for `X-Trace-Id` — identical to how `X-Correlation-Id` is handled. Confirmed live: `ui/src/app.js` generates its own `X-Trace-Id` client-side (`crypto.randomUUID()`, fixed once per browser tab) and sends it on every request; the gateway accepted it verbatim. For a value this project treats as an authoritative cross-service investigation anchor, that's a real gap — a client can set it to anything, including a fabricated or replayed value, undermining exactly the kind of trust an incident investigation needs. This is architecturally different from `X-Correlation-Id`, which is closer to an idempotency-style client-supplied token (the same accepted pattern as `Idempotency-Key` elsewhere in this codebase) and was left unchanged.

2. **`X-Trace-Id` never reached the async saga.** `ForceTraceFilter`'s own Javadoc documents that OTel span-tree propagation is deliberately scoped to synchronous HTTP hops only (gateway → order/catalog/inventory/user-service), not the Kafka-driven saga steps. That's a reasonable, accepted boundary for the *real* OTel span tree (extending it would mean instrumenting Kafka producer/consumer trace-context propagation, a materially larger change). But `X-Trace-Id` as a plain log-correlation field had no equivalent design decision — it simply wasn't threaded through the outbox/Kafka-header mechanism `X-Correlation-Id` already uses (ADR-0032), so `payment-service`'s log lines for a saga step showed `traceId=` blank even when `order-service`'s log line for the same `orderId` had a real value.

## Decision

**1. `X-Trace-Id` is now unconditionally generated at the gateway.** `CorrelationTraceGatewayFilter` no longer reads the incoming request's `X-Trace-Id` header at all — every request gets a fresh `UUID.randomUUID()`, always. `X-Correlation-Id` keeps its existing client-honoring behavior; only `X-Trace-Id`'s trust model changed.

**2. `X-Trace-Id` now propagates across the entire saga, not just synchronous HTTP hops** — reusing the exact mechanism `X-Correlation-Id` already has (ADR-0032), not a new one:
- `OrderCorrelationScope.forOrder(...)` gained a `traceId` parameter (old two-argument overload kept, degrading to leaving `traceId` unset — same graceful-degradation posture as `correlationId`'s own orderId fallback).
- Each service's `OutboxEvent` entity gained a `traceId` column (new Flyway migration per service: `order-service` V5, `inventory-service` V5, `payment-service` V4).
- Each `EventPublisher` (`OrderEventPublisher`, `InventoryEventPublisher`, `PaymentEventPublisher`) captures `MDC.get(MDC_TRACE_ID_KEY)` onto the outbox row at write time, same moment `correlationId` is captured.
- Each `OutboxPoller` adds a `traceId` Kafka message header alongside the existing `correlationId` one.
- Each `@KafkaListener` method across `OrderSagaConsumer`, `InventorySagaConsumer`, `PaymentSagaConsumer`, and `NotificationEventConsumer` now also reads a `traceId` header and passes it into `OrderCorrelationScope.forOrder(...)`.

This deliberately does **not** change the real OTel span-tree scope boundary `ForceTraceFilter`'s Javadoc documents — Tempo's hierarchy view still only covers the synchronous HTTP portion of a request. What changes is that the same gateway-generated `X-Trace-Id` value is now a genuine end-to-end **log**-correlation field: `{app=~".+"} |= "<trace-id>"` in Loki now finds every hop of a saga, not just the synchronous leg, closing exactly the gap that made the original investigation ("why can't I find this ID") harder than it should have been.

### Why not extend the real OTel span tree across Kafka instead

Considered and rejected for this change: instrumenting Kafka producer/consumer OTel context propagation (W3C trace context as message headers, consumed via the OTel Kafka instrumentation) would give a *true* single span tree across the whole saga, which is strictly more powerful than a shared log field. Rejected here because it's a materially larger, riskier change (new instrumentation dependency, correctness of context injection/extraction across an at-least-once, poller-mediated Kafka publish rather than a direct produce call) that wasn't part of what was asked, and the log-field propagation implemented here already closes the actual reported gap (searchability) with a much smaller, already-proven mechanism. Worth revisiting as a genuine follow-up if full cross-service span hierarchy for the async leg becomes a real need.

## Regression guard

- `CorrelationTraceGatewayFilterTest` (2 new cases): `clientSuppliedTraceIdIsNeverHonored` — a spoofed `X-Trace-Id` header is asserted to never reach the downstream request or the response, verified against a real `MockServerWebExchange`/`GatewayFilterChain`, not just the decision logic in isolation. `traceIdIsGeneratedEvenWhenClientSendsNone` — a trace ID is always present even with no incoming header at all.
- Full test suite (61 tests across `common-lib`, `api-gateway`, `order-service`, `inventory-service`, `payment-service`, `notification-service`, `catalog-service`, `user-service`) passes unchanged, including each service's Flyway migration test (the new `V5__outbox_trace_id.sql`/`V4__outbox_trace_id.sql` migrations run cleanly as part of each `ApplicationTests` context load).
- Live verification pending redeploy: place a real order, confirm `payment-service`'s and `notification-service`'s log lines for that order now carry the same non-blank `traceId=` as `order-service`'s.

## Consequences

- Positive: closes a real trust gap (client-supplied trace ID) with a minimal, symmetric fix — the same pattern this project already uses elsewhere (server-side enforcement over client-side convenience, see ADR-0048's identical reasoning for `X-Force-Trace`). Also closes a real observability gap — the entire saga is now searchable by one ID in Loki, not just the synchronous leg.
- Negative / accepted trade-off: three new Flyway migrations (one per outbox-owning service) and a few new Kafka message header bytes per event — negligible at this system's scale (ADR-0036: 20 active users). `X-Trace-Id` and the real OTel trace ID remain two different values by design (ADR-0023) — this ADR doesn't change that, only closes the propagation and trust gaps within the existing two-ID model.
- Follow-up required: none currently open. A genuine OTel-Kafka-instrumented span tree across the async saga remains a valid, larger future enhancement if ever needed (see "Why not" above) — not scheduled.

## Related

- Related: ADR-0023 (correlation ID and trace ID are distinct — this ADR closes gaps within that model, doesn't change it), ADR-0032 (the `correlationId` outbox/Kafka-header propagation mechanism this ADR extends to `traceId`), ADR-0048 (the same "server-side enforcement, not client-trusted convenience" pattern applied to a different header), ADR-0043 (the synchronous-hop OTel span-tree fix this ADR's async log-field propagation deliberately doesn't attempt to replicate)
