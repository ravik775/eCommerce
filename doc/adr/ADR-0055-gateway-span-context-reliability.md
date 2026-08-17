# ADR-0055: Fix unreliable Span.current() read in the gateway's trace-ID filter

**Status**: Accepted
**Date**: 2026-08-17 13:45 IST
**Deciders**: Solution/Security Architect

## Context

A live-captured request (`GET /order/customer/4`) showed the gateway's response `X-Trace-Id` (`755a2893-95a4-4f88-a010-e67b4b5fcebb`, a UUID) not matching any real Tempo trace — querying Tempo with it correctly returned zero results. Investigation traced this to `CorrelationTraceGatewayFilter.filter()` calling `Span.current().getSpanContext().getTraceId()` as **plain synchronous Java, executed during Spring Cloud Gateway's filter-chain assembly** (`FilteringWebHandler` calling every `GlobalFilter.filter()` method in a loop to build the composed `Mono` chain) — not as a Reactor operator callback.

Reactor's automatic context propagation (`Hooks.enableAutomaticContextPropagation()`, backed by `io.micrometer:context-propagation:1.1.4`, confirmed present and auto-enabled by Spring Boot 3.4.4) only re-threads the correct OTel `Context` onto the ThreadLocal around operators **Reactor itself invokes at subscription time**. Code that runs during eager chain construction has no such guarantee — it reads whatever `Context` happens to be on the current thread at that exact moment, which may belong to a different request entirely, or be empty.

This is a documented, reproducible gap in the current (2025-2026) Spring Cloud Gateway + Micrometer Tracing ecosystem, not unique to this codebase:
- [spring-projects/spring-boot#38615](https://github.com/spring-projects/spring-boot/issues/38615) — `Span.current()` null/invalid in WebFlux `Mono`-returning code, traced to the same `spring.reactor.context-propagation=auto` mechanism.
- [spring-cloud/spring-cloud-gateway#3904](https://github.com/spring-cloud/spring-cloud-gateway/issues/3904) — "Unable to export Micrometer traces via OpenTelemetry" on current Spring Boot 3.5.x / Spring Cloud 2025.0.0.
- [open-telemetry-java-instrumentation#10648](https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/10648) — Spring Cloud Gateway's `GlobalFilter` chain specifically not propagating context reliably.

Current industry guidance (2026) draws a clear line: the OpenTelemetry **Java agent** bytecode-instruments Reactor/Netty internals directly and avoids this class of problem entirely; the **library/Observation-API path this project uses** (`micrometer-tracing-bridge-otel`, no Java agent — confirmed) requires code to explicitly defer any `Span.current()`/context read to a point Reactor actually invokes as an operator, rather than trusting ambient ThreadLocal state during eager assembly.

## Decision

1. **Wrapped the entire filter body in `Mono.defer(() -> doFilter(exchange, chain))`.** This moves execution — including the `Span.current()` read — from "runs eagerly during `FilteringWebHandler`'s chain-assembly loop" to "runs as a `Supplier` Reactor invokes at subscription time," which is inside the properly-propagated context window per the sources above. This is the documented-correct fix (defer context reads to subscription time), not a workaround.

2. **Added visibility for the fallback path.** When `Span.current()` genuinely has no valid span (the `TraceId.isValid()` check fails), the filter now logs a `WARN` and an `AuditLogger` event (`TRACE_ID_FALLBACK_UUID`) instead of silently degrading to a random UUID. Before this, the only way to detect the fallback firing was manually diffing a captured `X-Trace-Id` against Tempo, as happened here. Now its live frequency is directly observable/countable in Loki, which also tells us empirically whether fix #1 eliminated it or only reduced it.

## Regression guard

- Existing `CorrelationTraceGatewayFilterTest` cases (`clientSuppliedTraceIdIsNeverHonored`, `traceIdIsGeneratedEvenWhenClientSendsNone`, ordering test) pass unchanged — `.block()` still triggers subscription of the now-deferred `Mono`, so the unit-test harness (no OTel SDK wired, `Span.current()` always invalid) continues to exercise the fallback path correctly.
- New `fallbackToRandomUuidIsAuditLogged` test: uses a Logback `ListAppender` to assert the WARN line is genuinely emitted when the fallback fires (which is every filter invocation in this unit-test context, by construction) — verifies the mechanism, not just that a UUID was generated.
- `./mvnw -pl api-gateway -am test`: 16/16 pass, `BUILD SUCCESS`.
- Live re-verification pending: after redeploy, place several real orders and confirm via the new `TRACE_ID_FALLBACK_UUID` audit event (or its absence) in Loki whether the fallback still fires at all, and if so, how often — this is the actual proof the fix works, not just that tests pass.

## Consequences

- Positive: fixes the actual, documented root cause rather than a symptom — `X-Trace-Id` should now reliably match Tempo's real trace ID for the synchronous leg of every request.
- Positive: even if the fallback still fires occasionally (e.g., under specific concurrency/scheduling conditions not fully eliminated by deferring to subscription time), it is now a visible, auditable, countable event instead of a silent, undetectable one.
- Negative / accepted: this does not change the underlying architectural choice (library instrumentation over the Java agent) — if this class of context-propagation bug recurs elsewhere in the gateway's reactive code, adopting the OpenTelemetry Java agent is the heavier, structural fix, noted here as a candidate escalation, not undertaken now since it wasn't justified by a single filter's bug.

## Related

- ADR-0052: the trace-ID generation/propagation decision this ADR corrects a reliability bug in.
- ADR-0043: the original filter-ordering fix (`Ordered.LOWEST_PRECEDENCE - 1`) that made `Span.current()` valid *in principle* at this point in the chain — this ADR fixes the remaining gap between "valid in principle" and "reliably read in practice."
- ADR-0048: `ForceTraceFilter`'s Javadoc documents the servlet-side equivalent ordering lesson this ADR's WebFlux-side counterpart extends.
