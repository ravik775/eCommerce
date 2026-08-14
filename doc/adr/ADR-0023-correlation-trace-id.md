# ADR-0023: Correlation ID and Trace ID — distinct headers, distinct guarantees

**Status**: Accepted
**Date**: 2026-08-13
**Deciders**: Solution/Security Architect

## Context

Requirement: every request carries a correlation ID (client-suppliable, generated if missing, echoed on every response regardless of outcome, **not** guaranteed unique) and a trace ID (generated at the system entry point — the API Gateway — unique at least within a tenant, same echo behavior otherwise). These are deliberately two different identifiers, not one renamed.

## Why Two Separate IDs, Not One

- A **correlation ID** is business-level and often client-supplied: a client (or an upstream system) may deliberately reuse the same correlation ID across several distinct calls that form one logical business operation (e.g., a checkout session spanning cart → order → payment calls) — reuse is expected, not a bug.
- A **trace ID** is infrastructure-level: it must uniquely identify one specific request's path through the system for distributed tracing (span correlation, latency analysis). Reusing a trace ID across unrelated requests would corrupt trace data.
- "A correlation ID links log entries and is human-accessible for support workflows... the trace ID links spans for APM tooling" — the two solve related but distinct problems and modern stacks commonly carry both simultaneously. ([Last9 — Correlation ID vs Trace ID](https://last9.io/blog/correlation-id-vs-trace-id/))
- W3C Trace Context (`traceparent` header) is the standard mechanism for trace ID propagation, and is what Micrometer Tracing/OpenTelemetry (already planned for Phase 7's observability work) generate and propagate automatically once wired in. ([tutorialpedia — Trace ID vs Correlation ID](https://www.tutorialpedia.org/blog/terminology-trace-id-vs-correlation-id/))

## Decision

- **`X-Correlation-Id`**: read from the incoming request if present; generated (UUID) at the API Gateway if absent. Propagated unchanged to every downstream service call. Always present on the response — success or failure — set as early as possible in the filter chain so it survives even an unhandled exception. Not required to be unique; no uniqueness check is performed.
- **`X-Trace-Id`**: generated once, at the API Gateway, for every inbound request (the system's true entry point) — a UUID, which is unique for all practical purposes, satisfying "unique at least within a tenant." Propagated unchanged to every downstream call. Same always-echoed response behavior as the correlation ID. Downstream services generate their own trace ID only defensively, for calls that somehow bypass the gateway (e.g., direct service-to-service testing) — never in the normal request path.
- Both are captured into each service's logging MDC so every log line during a request's handling carries both IDs, independent of whether full OpenTelemetry tracing (Phase 7) is wired in yet.
- Implementation lives once in `common-lib` (`org.bgm.common.correlation`), auto-configured into every Spring Boot service via a Spring Boot 3 `AutoConfiguration.imports` entry — no per-service filter registration boilerplate.

## Consequences

- Positive: no per-service reimplementation; consistent header names and behavior everywhere; failure responses are just as traceable as success responses (a common gap — many implementations only echo correlation IDs on 2xx responses).
- Negative / accepted trade-off: `X-Trace-Id` is a custom header today, not yet the W3C `traceparent` format — acceptable as a bridge until Phase 7 wires real Micrometer Tracing/OTel, at which point `X-Trace-Id` and `traceparent`'s trace-id segment should be reconciled (documented follow-up, not done now to avoid pulling Phase 7's full tracing stack in early).
- Follow-up required: when Phase 7 adds Micrometer Tracing, align `X-Trace-Id` with the OTel-generated trace ID rather than running two parallel ID schemes.

## Related

- Related architecture doc: `doc/architecture/04-application-architecture.md`
- ADR-0024 (idempotency — uses the correlation/trace filter's MDC context for logging duplicate-request detection)
