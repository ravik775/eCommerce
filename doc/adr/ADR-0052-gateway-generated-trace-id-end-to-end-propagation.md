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

## 2026-08-17 06:30 IST update — live incident: MDC key collision with Micrometer Tracing; X-Trace-Id now derived from the real OTel trace ID

Live end-to-end verification of the decision above (placing real orders, checking Loki for `traceId=` at every saga hop) found a genuine bug the earlier live-test pass hadn't caught: `payment-service`'s `AUDIT` log line for `PAYMENT_SUCCESS` consistently showed `traceId=` blank, while `order-service` and `inventory-service`'s lines for the same order showed a real value. Bisected precisely with three temporary debug log lines placed around `OrderServiceClient.getOrderAmount()` (the saga's one synchronous cross-service call, ADR-0007): the value was present in MDC immediately before `restTemplate.exchange(...)` and gone immediately after — narrowing the loss to inside that one call.

**Root cause**: `common-lib` depends on `micrometer-tracing-bridge-otel`, whose `MDCScopeDecorator` automatically manages the MDC key `"traceId"` itself, populated from the *real* OTel span context, activated on every auto-instrumented `Observation` scope — including an auto-instrumented outbound `RestTemplate` call. `CorrelationConstants.MDC_TRACE_ID_KEY` was also `"traceId"` — a direct, silent key collision. Micrometer's own scope push/pop around the outbound call clobbered the application-level value with its own (then correctly restored to *its* prior state on scope exit, not ours, since it has no knowledge of our value).

This was not a new bug introduced by this ADR's original decision — it was a **latent design flaw dating to ADR-0032** (when Micrometer Tracing was first added), which simply had no way to manifest until this ADR started actually writing an application value into `MDC["traceId"]` at all (the old `correlationId`-only propagation never touched that key). Digging further: this collision was *already* silently affecting the synchronous leg too — `order-service`'s own `AUDIT` line for `ORDER_CREATED` was displaying a 32-character lowercase-hex string (OTel's trace-ID format) rather than the gateway's `UUID.randomUUID()` (36-character, dashed) value, meaning Micrometer's real trace ID had already been leaking through as a side effect of filter ordering, for the *entire session's* earlier "successful" propagation tests — it just happened to survive because Micrometer's scope for `order-service`'s own inbound request stayed open for the whole request, only breaking visibly once `payment-service`'s outbound call created a second, nested scope.

### Decision

Two changes, addressing both the collision and a simplification opportunity it surfaced:

1. **Renamed `CorrelationConstants.MDC_TRACE_ID_KEY`** from `"traceId"` to `"appTraceId"` — a distinct MDC key Micrometer never touches. `LOGGING_PATTERN_LEVEL` (`k8s/base/configmap-common.yaml`) updated to show both fields side by side: `traceId=%X{traceId}` (Micrometer's real per-hop OTel trace ID, unchanged — Loki's `derivedFields` Tempo-jump depends on this exact field staying as-is) and the new `appTraceId=%X{appTraceId}` (this project's end-to-end saga identifier). Every other reference in the codebase goes through the symbolic constant, so this rename required no other code changes.

2. **`CorrelationTraceGatewayFilter` now derives `X-Trace-Id`'s value from the real OTel trace ID** (`Span.current().getSpanContext().getTraceId()`) instead of an unrelated `UUID.randomUUID()`, falling back to a random UUID only if `Span.current()` is somehow invalid (shouldn't happen given this filter's late ordering, ADR-0043, but never propagate an all-zeros ID if it does). Proposed independently by the user reviewing this same incident — and validated by the investigation above: the system was already accidentally behaving this way for the synchronous leg, just unreliably; making it deliberate removes a moving part (no separate ID-generation scheme to reason about) and means the *same* ID a user finds in a Loki `appTraceId=` search is *also* what they'd search Tempo with for that request's span tree — a genuine simplification, not just a bug fix.

This does **not** create a real, unified OTel span tree across the async Kafka saga — `appTraceId`'s value is still just a plain string carried via Kafka headers into services with no active OTel span relationship to the gateway's original one (same documented boundary as this ADR's original decision and `ForceTraceFilter`'s Javadoc). It does mean that string happens to be the same one Tempo uses for the synchronous portion, which is strictly more useful than an arbitrary UUID for that leg, without attempting to solve the harder cross-Kafka span-continuity problem.

### Regression guard

`CorrelationTraceGatewayFilterTest`'s existing two cases (`clientSuppliedTraceIdIsNeverHonored`, `traceIdIsGeneratedEvenWhenClientSendsNone`) still pass unchanged — in a unit-test context with no OTel SDK wired, `Span.current()` correctly falls back to the random-UUID path, still non-blank and still distinct from any spoofed value. Full suite (61+ tests across 8 modules) re-run after this correction. Live re-verification: place a fresh order, confirm `payment-service`'s `AUDIT` line now shows a non-blank `appTraceId` matching every other hop's value for that order.

### Consequences (update)

- Positive: closes a real, live-reproduced bug that silently affected trace-ID reliability since ADR-0032 — not just this ADR's own async-saga work. The OTel-trace-ID-reuse simplification removes a redundant ID-generation mechanism and directly improves the "trace this order as a hierarchy" workflow this session's earlier investigation was trying to support.
- Negative / accepted trade-off: two similarly-named MDC/log fields now exist (`traceId` = Micrometer's real per-hop OTel ID, `appTraceId` = this project's end-to-end saga ID) — a small ongoing documentation burden, but the alternative (silently colliding) is worse, and the two fields do serve genuinely different purposes (per-hop OTel trace vs. cross-saga log correlation).
- Follow-up required: none currently open.

## Related (update)

- Additionally related: ADR-0032 (the actual origin of the collision — `micrometer-tracing-bridge-otel` was added there, three ADRs before the field it would eventually collide with existed)
