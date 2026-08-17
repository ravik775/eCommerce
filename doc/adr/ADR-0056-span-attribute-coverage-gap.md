# ADR-0056: Close the real span-attribute coverage gap (ADR-0053 was incomplete)

**Status**: Accepted. api-gateway's remaining gap (spans not reaching Tempo at all for proxied routes, discovered during ADR-0063's investigation) closed by [ADR-0064](./ADR-0064-otel-java-agent-for-gateway-proxied-spans.md) (2026-08-17 21:05 IST) — live-verified via real Tempo trace data, not just theoretically fixed. This ADR's own `orderId`-attribute regression check was also corrected to stop checking api-gateway for an attribute it deliberately never sets by design (see this ADR's Decision below).
**Date**: 2026-08-17 13:55 IST
**Deciders**: Solution/Security Architect

## Context

ADR-0053 was declared complete after adding `SpanAttributeEnrichmentFilter` and verifying it worked for one HTTP request. It was not actually complete, and re-declaring it done without checking the rest of the system was a real mistake — caught only when directly querying Tempo's own tag index (`GET /api/search/tags` against the live Tempo instance) and finding `correlationId`/`orderId`/`appTraceId` present for **order-service only**, absent for inventory-service, payment-service, notification-service, and the gateway.

Root cause, confirmed by reading the code and cross-checking against `/api/search` filtered per service:

`SpanAttributeEnrichmentFilter` is a **Servlet `OncePerRequestFilter`** — it can only ever run for a request that passes through that service's own inbound HTTP servlet filter chain. But:

- **inventory-service, payment-service, notification-service** set `correlationId`/`orderId`/`appTraceId` **exclusively** inside `OrderCorrelationScope`, invoked from `@KafkaListener`/`@RabbitListener` methods — a Kafka or RabbitMQ message delivery **never passes through any Servlet filter chain at all**. There is no inbound HTTP request for the filter to attach to, structurally, regardless of how correctly the filter itself is written or deployed.
- **api-gateway** is WebFlux, not Servlet — `SpanAttributeEnrichmentFilter`'s auto-configuration is `@ConditionalOnWebApplication(type = SERVLET)` and never even registers there.
- **order-service** appeared to work only because its one HTTP-triggered path (order creation) happens to set `orderId` via direct `MDC.put(...)` inside the same request thread the filter is watching — the one case that happens to line up with what a Servlet filter can see.

This was not a partial-but-adequate fix — for 3 of 4 services in the saga, and the gateway itself, it did nothing at all. The earlier ADR-0053 report of "done" was wrong because it was verified against a single request/service instead of checked against the system as a whole.

## Decision

Fixed the enrichment at the actual shared choke point instead of the HTTP-only one:

1. **`OrderCorrelationScope.forOrder(...)` (common-lib) now also stamps `Span.current()`** with `correlationId`/`orderId`/`appTraceId`, at the exact point it already sets the equivalent MDC values. This is the one call site `InventorySagaConsumer`, `PaymentSagaConsumer`, `NotificationEventConsumer`, `NotificationDispatchWorker`, and `OrderSagaConsumer` **all** already funnel through — fixing it here fixes all of them at once, and doesn't depend on any assumption about whether Kafka/RabbitMQ listener-container Observation-API instrumentation is active. `Span.current()` at this point resolves to whatever span is genuinely active for that thread — a real listener span if that instrumentation exists, or OTel's own no-op span otherwise (`setAttribute` on a no-op span is a documented no-op, not an error) — so this is safe regardless.

2. **`CorrelationTraceGatewayFilter` (api-gateway) now also stamps its own span** with `correlationId`/`appTraceId` — both values are already computed locally in that method (ADR-0055's `Mono.defer` fix made `Span.current()` reliable there), so this cost nothing extra. `orderId` is deliberately not set here — the gateway never learns it; order-service assigns it after the gateway's span has already started.

`SpanAttributeEnrichmentFilter` (ADR-0053) is unchanged and still needed — it's still the only mechanism that captures order-service's own HTTP-triggered `orderId` (set via direct `MDC.put`, not `OrderCorrelationScope`).

## Regression guard — and what "verified" actually means here

Given the original mistake was declaring a partial fix complete, this ADR's verification step is the actual proof, not the tests:

- New `OrderCorrelationScopeTest` (common-lib, 4 cases, using the same `InMemorySpanExporter` pattern as `SpanAttributeEnrichmentFilterTest`): attributes are correctly recorded on the active span; `appTraceId` is correctly omitted (not set to an empty string) when no traceId was propagated; `correlationId` correctly degrades to the order ID when neither was propagated; setting attributes is a safe no-op when there is no active span at all (the exact situation an uninstrumented Kafka listener thread is in).
- `./mvnw test` (all 11 modules): **BUILD SUCCESS**, 13:00 min, no regressions.
- **Live verification (the part that actually matters, given the history here)**: still pending as of this commit — rebuild/redeploy of the 5 affected services (api-gateway, order-service, inventory-service, payment-service, notification-service) has not happened yet. This ADR's Consequences section, and the claim that the gap is closed, is **not confirmed until** a fresh order is placed post-redeploy and Tempo's `/api/search` is queried directly per service (`{resource.service.name="inventory-service" && span.orderId != nil}`, repeated for payment-service, notification-service, api-gateway) showing a real match for each — not just for order-service, which is the one case that already worked before this ADR.

## Consequences

- Positive: once live-verified, this is the first time these attributes will actually be searchable in Tempo's Search-tab Tags dropdown for the majority of the saga's services — Tempo auto-discovers available tag names from ingested spans, so no separate "enable search" step exists or is needed once the attributes are genuinely present.
- Negative / process lesson: ADR-0053 should have been verified against every service in the saga, not one request against one service, before being marked done. This ADR exists specifically because that didn't happen — recorded here so the same mistake (declaring a fix complete based on a single, easiest-case test) isn't repeated.
- Follow-up still open: whether Kafka/RabbitMQ listener-container spans exist at all as their own distinct spans (separate from whatever HTTP/outbound-call spans happen to be active on that thread) hasn't been independently confirmed — `OrderCorrelationScope`'s stamping is correct either way (attaches to whatever span is current), but if no listener span exists, the attribute lands on the no-op span and is invisible, same as today. Confirming this and, if needed, explicitly instrumenting Kafka/RabbitMQ listener invocations as their own spans is out of scope for this ADR and noted as a candidate next step depending on what the live verification above actually shows.

## Related

- ADR-0053: the original, incomplete fix this ADR corrects.
- ADR-0052: the trace-ID propagation work `OrderCorrelationScope` already existed to support — this ADR extends the same call site's responsibility to span attributes, not just MDC.
- ADR-0055: the gateway span-context reliability fix this ADR's gateway-side change depends on (`Span.current()` must be valid at the point this filter runs, which ADR-0055 fixed).
