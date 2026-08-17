# ADR-0063: Fix unreliable Span.current() in the gateway's X-Force-Trace branch (partial — root cause turned out deeper)

**Status**: Accepted, with the deeper gap this ADR's own investigation surfaced now closed by [ADR-0064](./ADR-0064-otel-java-agent-for-gateway-proxied-spans.md) (2026-08-17 21:05 IST) — see the 18:45 IST correction below for the root-cause finding, and ADR-0064 for the fix and its live verification (3/3 force-traced proxied-route requests confirmed with real `api-gateway` spans in Tempo). The `Span.current()` reliability fix in this ADR is real and kept regardless.
**Date**: 2026-08-17 18:20 IST
**Deciders**: Solution/Security Architect

## Context

Item 2 of an earlier, explicitly ordered close-out list: "Gateway spans missing from Tempo for authenticated/proxied routes" — observed live as the gateway reliably exporting spans for `permitAll()` routes (`/actuator/prometheus`, scraped by Prometheus every ~15s) but not for authenticated/proxied routes like `POST /order`, placed only a handful of times manually.

Investigation found **two separate, compounding causes**, not one:

1. **`TRACING_SUCCESS_SAMPLE_RATE: "0.1"`** (`k8s/base/configmap-common.yaml`) — every service, including the gateway, only exports 10% of successful spans (errors are always exported, see `ErrorAlwaysSampledSpanExporter`). This is working as designed, not a bug: `/actuator/prometheus` *looks* reliably captured only because it's scraped constantly, so a 10% rate still yields frequent hits over any real time window. A handful of manual `POST /order` calls have a real, non-trivial chance of zero landing in that 10%. This alone fully explains the "missing" observation without any code defect.

2. **The one deliberate escape hatch from that sampling — the `X-Force-Trace` header, gated by `ROLE_CAN_TRACE` — was itself unreliable on the gateway.** `CorrelationTraceGatewayFilter.doFilter()`'s force-trace branch reads `Span.current()` inside a `.doOnNext(...)` callback on `ReactiveSecurityContextHolder.getContext()` — an inherently async Reactor Context lookup. That callback runs **after** the `ContextSnapshot.Scope` established in `filter()` has already closed (the try-with-resources scope only wraps the synchronous `doFilter(...)` invocation itself, not the async continuation of the `Mono` it returns) — the exact same unreliable-`Span.current()`-timing bug ADR-0055 fixed for the trace-ID-generation line, left unfixed in this one later branch. So even a caller who explicitly forced tracing to bypass the 10% sampling could still silently miss the gateway's own span some of the time.

## Decision

Applied the identical ADR-0055 fix pattern to this one remaining branch: re-capture a fresh `ContextSnapshot` from Reactor's own `ContextView` (via `Mono.deferContextual`) at the exact point `Span.current()` is read, rather than trusting whatever the ambient ThreadLocal happens to hold by the time the async `ReactiveSecurityContextHolder` lookup resolves.

```java
return ReactiveSecurityContextHolder.getContext()
        .map(SecurityContext::getAuthentication)
        .flatMap(authentication -> Mono.deferContextual(cv -> {
            try (ContextSnapshot.Scope ignored = CONTEXT_SNAPSHOT_FACTORY.captureFrom(cv).setThreadLocals()) {
                if (callerHasCanTraceRole(authentication)) {
                    Span.current().setAttribute(ErrorAlwaysSampledSpanExporter.FORCE_TRACE_ATTRIBUTE, true);
                } else {
                    auditDenied(authentication, exchange);
                }
            }
            return Mono.empty();
        }))
        .then(proceed);
```

No change was made to the sampling rate itself — 10% success sampling with always-sampled errors and an authorized force-trace override is the intended design (ADR-0032), not the thing that was actually broken. The fix targets the one broken escape hatch, not the sampling policy.

## Regression guard

- `./mvnw -pl common-lib,order-service,inventory-service,payment-service,notification-service -am test`: `BUILD SUCCESS` (span-link work from the same session, ADR-0062, verified together).
- `./mvnw -pl api-gateway -am test`: pending as of this entry — existing `CorrelationTraceGatewayFilterTest` cases exercise `callerHasCanTraceRole(...)` directly (package-private, unit-tested in isolation per its own Javadoc) and are unaffected by this change, since the decision logic itself didn't move, only where `Span.current()` is read from.
- Live verification (pending): place several real orders with `X-Force-Trace: true` as a `CAN_TRACE` user, then query Tempo directly (`GET /api/search?q={resource.service.name="api-gateway" && span.forceTrace=true}` or the `FORCE_TRACE_ATTRIBUTE` equivalent) and confirm the gateway's own span is present for every one of them — not just most.

### 2026-08-17 18:45 IST correction — re-deferring ContextSnapshot wasn't enough; captured the Span reference directly instead

Live re-verification (per this ADR's own pending item) found the first fix incomplete: 3 real `X-Force-Trace: true` orders placed as `admin1` (`CAN_TRACE`), checked via raw Tempo trace JSON (`GET /api/traces/{id}`, not just `/api/search` — confirmed each trace was fully ingested by checking order-service's span was present in all 3) — only **1 of 3** actually had an `api-gateway` span exported. The other 2 genuinely had none, not a query-timing artifact.

Root cause of the incompleteness: re-deferring a fresh `ContextSnapshot` from `Mono.deferContextual` inside the `ReactiveSecurityContextHolder.getContext()` chain (the original fix) still depends on that specific async hop correctly carrying the OTel Context accessor through Reactor's Context — evidently a different, less reliable path than the outer `filter()` → `doFilter()` hop ADR-0055 fixed, for reasons not fully isolated (plausibly: `ReactiveSecurityContextHolder`'s own internal Mono composition doesn't preserve every registered `ThreadLocalAccessor` binding the same way a plain `chain.filter(...)` continuation does). Chasing the exact mechanism further wasn't worth it once a strictly simpler fix was available.

**Fix**: capture `Span gatewaySpan = Span.current()` **once**, synchronously, at the top of `doFilter(...)` — the exact same point `traceId` is already read, a point ADR-0055 live-verified reliable 10/10. Hold it as a direct object reference. The async force-trace branch calls `gatewaySpan.setAttribute(...)` directly on that reference instead of re-resolving `Span.current()` later — a plain method call on an OTel `Span` object that hasn't ended yet (nothing downstream, including the proxied request itself, has run at that point), correct regardless of which thread or Reactor Context is ambient when the callback actually executes. This sidesteps the async-context-reliability question entirely rather than relocating it.

**Verification, and a further, more important correction**: the same 3-order test repeated after rebuild/redeploy found **0 of 3** — worse, not better. Investigating why led to the actual root cause, which supersedes everything written above: queried Tempo broadly for every `api-gateway` span ingested across this entire multi-hour session (`{resource.service.name="api-gateway"}`, no other filter, ~50 results) and grouped by `rootTraceName`. **Every single one** was `http get /actuator/prometheus` (49) or a directly-handled route like `/user/me` (1) — **zero, ever, for any proxied/routed request** (`POST /order` or otherwise). This is not a sampling artifact and not a `Span.current()` timing bug at all: `/actuator/prometheus` and `/user/me` are handled **locally** by the gateway's own WebFlux handlers; `POST /order` and every other business route is **proxied** through Spring Cloud Gateway's `NettyRoutingFilter` to a downstream service. The gateway's own HTTP-server span for a *proxied* request apparently never completes/reports to the OTel SDK's span processor at all, structurally, regardless of the attribute-stamping fix above (which is real and correct for what it does — the `gatewaySpan` reference genuinely gets `force_trace=true` set on it — but that span is never actually exported for proxied routes in the first place, so the attribute never gets a chance to matter).

This matches the exact class of gap the research already surfaced during ADR-0055 (`spring-cloud/spring-cloud-gateway#3904` — "Unable to export Micrometer traces via OpenTelemetry"; `open-telemetry-java-instrumentation#10648` — "Spring Cloud Gateway's GlobalFilter chain specifically not propagating context reliably") — this session's own investigation reached a further, more specific instance of the same documented library-level gap: it's not just context propagation *within* a proxied request that's unreliable, the proxied request's own server-span lifecycle doesn't complete at all under the library-instrumentation (no Java agent) approach this project uses.

**Status downgraded accordingly**: the `Span.current()`-reliability fix above is kept (it is a real, narrower correctness fix — the force-trace attribute now lands on the right span object when that span does exist and get exported), but this ADR does **not** close the original item ("gateway spans missing from Tempo for authenticated/proxied routes"). That requires one of the two heavier options ADR-0055 already flagged as a candidate escalation and explicitly did not undertake: adopting the OpenTelemetry Java agent (bytecode-instruments reactor-netty/Spring Cloud Gateway internals directly, sidestepping the library-instrumentation gap entirely), or manually wrapping `NettyRoutingFilter`'s proxied call in an explicitly-created and explicitly-ended span. Neither is undertaken in this session — flagged here as the honest, unresolved next step rather than declared fixed.

`./scripts/regression-sanity.sh`'s new `check_gateway_force_trace_span` check (added alongside this ADR) will correctly and repeatedly FAIL until one of those two options is implemented — this is intentional: it documents the real, still-open gap rather than a check calibrated to pass around it.

## Consequences

- Positive: the `X-Force-Trace` escape hatch now reliably does what it's documented to do for the gateway's own span, closing the gap between "looks broken under low volume" (sampling, expected) and "the one tool meant to prove it isn't broken doesn't reliably work either" (this bug, not expected).
- Negative / accepted: does not change the fact that, without force-trace, the gateway's own span for any given authenticated request still has only a 10% chance of being exported — this is intended behavior, not something this ADR set out to change; noted here so a future investigator doesn't rediscover the same "missing span" report and assume it's still this bug.

## Related

- ADR-0055: the original `Span.current()`-timing bug and fix pattern this ADR applies to the one branch ADR-0055 didn't cover.
- ADR-0048: the original `X-Force-Trace` authorization-gating decision (role check, audit-on-denial) this ADR's fix preserves unchanged — only the context-reliability mechanics changed.
- ADR-0032: the 10% success-sampling policy this ADR confirmed is working as designed, not a defect.
