# ADR-0053: Stamp correlationId/orderId/appTraceId/force_trace onto exported OTel spans

**Status**: Accepted
**Date**: 2026-08-17 10:00 IST
**Deciders**: Solution/Security Architect

## Context

Inspecting a live Tempo trace for a real checkout request (order 58, correlationId `cb4e45f6-...`) in Grafana showed only the default HTTP instrumentation attributes on the root span:

```
method, uri, status, outcome, exception, http.url
```

`correlationId`, `orderId`, and `appTraceId` — all present in MDC and visible in the corresponding Loki log lines for the exact same request — were absent from the span itself. So was `force_trace`, except on a different, nested span (`ForceTraceFilter` tags it there, correctly, but nowhere else).

Root cause: MDC (thread-local, feeds `LOGGING_PATTERN_LEVEL`, ADR-0052) and the OTel span (in-memory span object, feeds Tempo export) are two independent systems that happen to carry overlapping values. Nothing in the codebase ever called `Span.current().setAttribute(...)` to bridge MDC values onto the span — the two systems were only ever correlated indirectly, by a human copying a `traceId` value out of a log line and pasting it into Tempo's search box.

## Decision

New `SpanAttributeEnrichmentFilter` (common-lib, `org.bgm.common.tracing`), registered via `ForceTraceFilterAutoConfiguration` alongside the existing `ForceTraceFilter`, for every Servlet-based service:

- Runs in the **post-chain** phase (`finally` block after `chain.doFilter(...)` returns), not pre-chain — deliberately, so it reads MDC only after the controller has fully executed. This matters specifically for `orderId`: `OrderService` only writes it into MDC once the order row is persisted and its ID is known, partway through handling the order-creation request. A pre-chain read (the way `ForceTraceFilter` reads its header, which is known upfront) would miss it.
- Registered at `Integer.MAX_VALUE` — one later (innermost) than `ForceTraceFilter`'s `Integer.MAX_VALUE - 1` — for the same `Span.current()`-validity reason `ForceTraceFilter`'s own Javadoc documents (Spring's HTTP tracing instrumentation must have already created the real span), plus needing to be the *first* filter to run on the way back out so its MDC read happens as early as possible after the controller returns.
- Stamps `correlationId`, `orderId`, `appTraceId` from MDC onto `Span.current()` — only when the corresponding MDC value is present and non-blank, so a request with no order context (e.g. catalog browsing) doesn't get a noisy empty attribute.
- Also stamps `force_trace` — reusing `ForceTraceFilter.callerHasCanTraceRole()` (changed from `private` to package-private `static`, pure visibility change, no logic touched) as the single source of truth for the same authorization decision, rather than duplicating the CAN_TRACE check in a second place. `ForceTraceFilter` remains the sole component that decides whether a trace is *force-exported*; this filter only decides whether the *display* attribute is *also* set on this filter's own span for the reader's convenience — two different concerns kept in two classes on purpose (security decision vs. display concern), sharing one authorization check.

Scope: Servlet-based services only (same `@ConditionalOnWebApplication(type = SERVLET)` gate `ForceTraceFilter` already uses) — api-gateway (WebFlux/reactive) is out of scope for this change, consistent with `ForceTraceFilter` itself already being servlet-only. Extending equivalent enrichment to the gateway's reactive request path would need a different mechanism (no thread-local MDC in WebFlux) and is a genuine follow-up, not bundled here.

## Regression guard

- New `SpanAttributeEnrichmentFilterTest` (4 cases, using `SdkTracerProvider` + `InMemorySpanExporter` against a real `Span.makeCurrent()` scope, not mocks): attributes set correctly when MDC is populated during the chain (not before); attributes omitted (not set to empty/null) when the MDC field is absent; `force_trace` set when header present and caller has `ROLE_CAN_TRACE`; `force_trace` NOT set when caller lacks the role, even with the header present.
- `ForceTraceFilterTest` (existing, 6 cases) and `ForceTraceFilterAutoConfigurationTest` (existing, locks `ForceTraceFilter`'s own registration order) both pass unchanged — the `private` → `static` visibility change made no behavioral difference.
- Full multi-module test suite (`common-lib`, `order-service`, `api-gateway`, `user-service`, `catalog-service`, `inventory-service`, `payment-service`, `notification-service`, `config-server`, `service-discovery`) run via `./mvnw test`: **BUILD SUCCESS**, all modules pass.
- Live verification pending redeploy: place a real order, open its trace in Tempo, confirm `correlationId`/`orderId`/`appTraceId` now appear as span attributes on the root span (and `force_trace` when forced), not just in the corresponding Loki log lines.

## Consequences

- Positive: closes the gap that prompted this change — a Tempo trace is now self-describing (correlationId/orderId/appTraceId/force_trace visible directly in the span, no need to cross-reference Loki to learn which order a trace belongs to). Small, additive change — one new filter class, no changes to any existing request/response contract, no new headers, no new config.
- Positive: reuses the exact filter-ordering lesson already paid for by `ForceTraceFilter` (documented live-incident history in that class's own Javadoc) rather than re-discovering it — the new filter is safe from day one instead of needing its own "moved from early to late" incident.
- Negative / accepted limitation: does not cover the WebFlux gateway, and does not extend to spans generated inside the async Kafka saga legs (which, per ADR-0052, don't have real OTel span continuity across Kafka in the first place — see that ADR's "why not extend the real OTel span tree across Kafka" section). This enrichment only ever applies to spans that already exist on the synchronous HTTP path.
- Negative / accepted limitation: `appTraceId` as a span attribute is largely redundant with the span's own real trace ID on the synchronous leg (since ADR-0052 made the gateway derive `X-Trace-Id` from the real OTel trace ID) — it's still included for symmetry with `correlationId`/`orderId` and because it stops being redundant the moment a value diverges (e.g. a request that bypassed the gateway, or a Kafka-header-restored value on some future gateway-adjacent span).

## Related

- ADR-0032: original OTel/Micrometer Tracing integration, `ForceTraceFilter`'s force-export mechanism, `ErrorAlwaysSampledSpanExporter`.
- ADR-0043: `ForceTraceFilter`/`CorrelationTraceGatewayFilter` filter-ordering incident history — the precedent this change's ordering choice is built on.
- ADR-0052: `appTraceId` MDC key (post-collision-fix), end-to-end log-correlation propagation this change makes visible directly on spans for the synchronous leg.
