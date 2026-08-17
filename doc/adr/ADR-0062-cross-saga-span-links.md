# ADR-0062: Link every saga hop's span back to its origin via OTel span Links

**Status**: Accepted
**Date**: 2026-08-17 18:20 IST
**Deciders**: Solution/Security Architect

## Context

Earlier in this same investigation (ADR-0055/0056/0057/0059/0060/0061), the platform reached a state where every saga hop (order-service → inventory-service → payment-service → notification-service, over Kafka and RabbitMQ) carries a consistent `appTraceId` and produces its own real span (once Kafka/RabbitMQ Observation instrumentation was enabled, ADR-0057). But those spans are **structurally disconnected from each other in Tempo** — proven live across multiple real orders (74/76/77/78/79/80/81/82): each service hop gets its own, genuinely different Tempo trace ID, because the transactional outbox pattern (ADR-0007) publishes to Kafka asynchronously, up to `fixedDelay=1000` after the originating request's span has already ended. There is no OTel-level parent-child relationship possible across that gap — this is a structural consequence of the outbox pattern, not a bug to "just fix" in the propagation code.

When first asked whether this could be solved without adopting a heavier fix (the OpenTelemetry Java agent, or moving span creation into the outbox write itself), the initial answer overstated the difficulty and additionally recommended the wrong OTel primitive: parent-child linkage via `setParent()`. On direct challenge, re-verified against OpenTelemetry's own messaging semantic conventions and prior art (Datadog's public write-up on parent-child vs. span Links for messaging systems; Grafana Tempo's trace-view UI, confirmed to render span Links in the span detail panel):

- A span can only have **one** parent. This saga's `order-created` event fans out to inventory-service *and* (later) to payment-service, notification-service — there is no single valid "child" relationship to model with `setParent()`/a `Context`-level parent.
- OpenTelemetry's messaging semantic conventions **document span Links as the correct mechanism for exactly this fan-out scenario** — an association between spans that aren't in a strict parent-child tree.
- `Span.addLink(SpanContext)` is valid on an **already-started** span (no requirement that it happen before span creation), so it can be added inside `OrderCorrelationScope` — the single shared call site every saga consumer already funnels through (ADR-0056) — without needing to intercept or delay span creation anywhere.
- Capturing the real origin span ID is not hard: it's the same `Span.current().getSpanContext()` call already used for `traceId`, just also reading `.getSpanId()`.

## Decision

1. **Propagate `traceId` + `spanId`** (one new field, not a redesign) as an additional Kafka/RabbitMQ message header (`CorrelationConstants.MDC_SPAN_ID_KEY = "parentSpanId"`), using the exact same mechanism already proven for `correlationId`/`traceId`: a new `outbox_event.span_id` column (order-service `V6`, inventory-service `V6`, payment-service `V5`), populated at write time, read by `OutboxPoller` and attached as a Kafka header, read back via `@Header` on each `@KafkaListener`/`@RabbitListener`, and threaded through `OrderCorrelationScope.forOrder(...)`'s new 4-arg overload (3-arg overload kept, degrades to no link — same pattern as the existing 2-arg overload).

2. **Root-only capture, not a hop-by-hop chain.** `OrderEventPublisher` (order-service) is the *only* place that captures a fresh `Span.current().getSpanContext().getSpanId()` — it is always the saga's genuine root (a live synchronous Servlet HTTP request, not the gateway's WebFlux code ADR-0055 had to special-case). `InventoryEventPublisher`/`PaymentEventPublisher` never re-capture; they forward whatever's already in MDC unchanged. This means every hop's span links back to the *same* originating request span, giving Tempo a star topology (all hops → one root) rather than a chain — chosen because a chain would require every hop to also persist its own span ID at its own outbox-write time (a much larger change, symmetric complexity across all 3 services' write paths) for marginal benefit over knowing the true origin.

3. **`OrderCorrelationScope.forOrder(...)` calls `Span.addLink(...)`, never `setParent()`**, guarded by `TraceId.isValid()`/`SpanId.isValid()` (so a missing/malformed header degrades to no link, never a crash) — added at the same point attributes are already stamped (ADR-0056), so no new integration point was needed anywhere else.

## Regression guard

- `OrderCorrelationScopeTest` (common-lib): 3 new cases — a real 32-hex traceId + 16-hex spanId produces exactly one link with the correct `SpanContext`; an absent `parentSpanId` produces no link (back-compat with the 3-arg overload); a malformed `parentSpanId` produces no link rather than throwing.
- `./mvnw -pl common-lib,order-service,inventory-service,payment-service,notification-service -am test`: see verification note below.
- Live verification (pending as of this entry, to be added once redeployed): place a real order, fetch the raw Tempo trace JSON for the inventory-service/payment-service/notification-service spans (`GET /api/traces/{id}`) and confirm a non-empty `"links"` array pointing at order-service's root trace/span ID.

### 2026-08-17 19:05 IST update — live-verified, genuinely closed

Redeployed all 5 changed services (order/inventory/payment/notification-service, api-gateway — common-lib is a shared dependency, no separate image) via a fresh scratch-context `--no-cache` build, then ran `./scripts/regression-sanity.sh` end to end against the live cluster: **19 PASS, 1 WARN (unrelated, pre-existing ADR-0056 gap), 0 FAIL**, including the new `check_span_link_present` check passing genuinely.

Independently re-verified by hand, not just trusting the script: for the regression run's real order (`orderId=94`), queried Tempo's search API for inventory-service's span, then fetched that trace's full raw JSON directly (`GET /api/traces/{traceID}`) and found:
```
"links":[{"traceId":"y6Pq/atFa7EYucA7Yj0wuw==","spanId":"JqOHL+UPkIM="}]
```
a genuine, non-empty link (base64-encoded per Tempo's proto-JSON encoding, as expected) — confirming the design works end to end: order-service's root span ID survives the outbox-row → Kafka-header → MDC → `Span.addLink()` chain and lands as a real, queryable link on the downstream consumer's span. This is genuinely closed, not just unit-tested.

## Consequences

- Positive: Tempo's span-link UI now lets an operator jump from any saga-hop span back to the exact HTTP request that started it — closing the "not a full causal tree" gap identified in the prior discussion, using the spec-correct mechanism instead of a structurally wrong one.
- Negative / accepted: this is a **root reference, not a full waterfall** — Tempo still shows each hop as its own separate trace (linked, not nested), because genuinely unifying them into one trace tree would require either moving span creation into the outbox write itself or adopting the OpenTelemetry Java agent (noted as a candidate escalation in ADR-0055, not undertaken here — same reasoning: not justified without evidence the link-based approach is insufficient).
- Negative / accepted: adds one more column to three services' `outbox_event` tables and one more header to every Kafka/RabbitMQ message in the saga — small, symmetric, following the exact pattern already established for `traceId`, not a new propagation mechanism.

## Related

- ADR-0007: the outbox pattern whose async-publish timing is the structural reason no true parent-child link is possible.
- ADR-0052: the `traceId` propagation mechanism this ADR's `spanId` propagation reuses verbatim.
- ADR-0056: `OrderCorrelationScope.forOrder(...)` as the shared span-attribute (and now span-link) choke point across every saga consumer.
- ADR-0057: Kafka/RabbitMQ Observation instrumentation — the reason each hop has a real span to attach a link to at all.
