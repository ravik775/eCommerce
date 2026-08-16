# ADR-0043: OpenTelemetry tracing correctness — three filter-ordering/attribute-typing bugs, and the tests that guard against their return

**Status**: Accepted
**Date**: 2026-08-16
**Deciders**: Solution/Security Architect

## Context

ADR-0032 decided OpenTelemetry tracing, the `CAN_TRACE`-gated force-trace toggle, and error-always-sampled export — but three real implementation bugs shipped that silently broke each of those, discovered and fixed during a live verification pass this session. None of the three had a regression test at the time they were fixed. This ADR exists specifically so a future refactor that reintroduces any of them has both a written record of *why* the current behavior is correct and an automated test that fails loudly instead of the bug being rediscovered the same expensive way (interactive trace-by-trace debugging against a live Tempo instance).

## The three bugs

**1. `ForceTraceFilter` registered too early in the servlet filter chain.**
`ForceTraceFilterAutoConfiguration` originally set `registration.setOrder(Integer.MIN_VALUE + 1)`. At that point in the chain, Spring's own HTTP-server tracing instrumentation had not yet created the request's span, so `Span.current()` inside the filter returned an invalid/no-op span — `setAttribute(FORCE_TRACE_ATTRIBUTE, true)` was a silent no-op, and the real, later-created, actually-exported span never carried the attribute. The `CAN_TRACE` Settings toggle appeared to do nothing.

**2. `CorrelationTraceGatewayFilter` registered too early in the WebFlux filter chain, and never propagated trace context downstream at all.**
Same root cause as #1, plus a second, independent bug: even after fixing the ordering, nothing was injecting a W3C `traceparent` header into the proxied outbound request. Spring Cloud Gateway's `NettyRoutingFilter` does not do this automatically the way a `WebClient`-based call would. The practical effect: every hop (gateway, then each backend service) rooted its own independent OTel trace — Tempo never showed one connected tree, only per-service fragments, even though correlation IDs (a separate mechanism) were joining correctly in logs the whole time.

**3. `ErrorAlwaysSampledSpanExporter` only checked a `long`-typed HTTP status attribute.**
Micrometer Observation tags are string-typed; the OTel bridge exports them as OTel string attributes, not longs. `AttributeKey` is type-tagged, so an `AttributeKey.longKey("http.response.status_code")` lookup against a string-typed attribute of the same name silently returns `null`. Every 4xx response's span was exempt from the "always export failures" policy ADR-0032 decided, contradicting that decision's own stated requirement.

## Decision

All three fixed, verified live this session:
1. `ForceTraceFilterAutoConfiguration`: order moved to `Integer.MAX_VALUE - 1`.
2. `CorrelationTraceGatewayFilter`: order moved to `Ordered.LOWEST_PRECEDENCE - 1`, plus explicit `W3CTraceContextPropagator.getInstance().inject(...)` onto the proxied request builder.
3. `ErrorAlwaysSampledSpanExporter`: checks both `AttributeKey.longKey(...)` and `AttributeKey.stringKey(...)` for both the current and legacy semantic-convention key names, string-typed first.

**Regression guard, added the same session (not just documented after the fact)**:
- `ForceTraceFilterAutoConfigurationTest` (`common-lib`) — asserts the registered filter's order is `Integer.MAX_VALUE - 1` and explicitly not near `Integer.MIN_VALUE`.
- `CorrelationTraceGatewayFilterTest` (`api-gateway`) — asserts `getOrder()` returns `Ordered.LOWEST_PRECEDENCE - 1` and explicitly not `HIGHEST_PRECEDENCE`.
- `ErrorAlwaysSampledSpanExporterTest` (`common-lib`, 5 cases) — asserts both string- and long-typed 4xx/5xx attributes force-export, a healthy 2xx at 0% sample rate does not, and `force_trace`/OTel-native-ERROR status both force-export independently of the HTTP-status check.

These are unit tests of the *decision logic* (order values, exporter predicates), not full integration re-tests of the live trace pipeline — a genuinely different filter-chain timing bug in a future Spring Boot version could still slip past a unit test that only checks the order constant. The authoritative regression check remains a live trace: force-trace a request via the UI's Settings toggle, confirm a span in Tempo carries `force_trace: true` with a real parent span ID from the calling service. Do this before merging any change that touches `common-lib/tracing/*` or `CorrelationTraceGatewayFilter`.

## Consequences

- Positive: `CAN_TRACE`'s force-trace toggle, cross-service trace-tree linkage, and the "always export failures" policy all now work as ADR-0032 originally specified — and a future engineer changing filter ordering "for cleanliness" gets a failing test instead of a silently reintroduced bug.
- Negative / accepted trade-off: the unit tests check ordering *values*, not the actual runtime behavior those values produce (a WebFlux/Reactor context-propagation regression in a future Spring version wouldn't be caught by these tests alone) — the live-trace check above remains necessary for genuine confidence, not optional.
- Follow-up required: none currently open. If `CorrelationTraceGatewayFilter` or `ForceTraceFilterAutoConfiguration` needs to change order again for a legitimate new reason, update both the order value and its corresponding test in the same change — a test change with no corresponding "why" comment update is a signal to slow down, not speed through.

## Related

- Related: ADR-0032 (the original tracing/CAN_TRACE/error-sampling decisions these bugs broke), ADR-0035 (CAP/PACELC — unrelated architecturally, but the same session's broader observability review that found these)
