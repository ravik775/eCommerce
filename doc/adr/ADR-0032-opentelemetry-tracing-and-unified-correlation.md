# ADR-0032: OpenTelemetry distributed tracing, unified correlation ID, force-trace flag, and order-rate metrics

**Status**: Accepted
**Date**: 2026-08-15
**Deciders**: Solution/Security Architect

## Context

Four related gaps, found via a structured audit against a new requirement ("same correlation/tracking ID front-to-back, real distributed trace with span hierarchy, a role-gated force-trace flag, sampling policy, and order-rate metrics"):

1. **Correlation ID is not actually stable across one checkout flow.** `OrderCorrelationScope` (built earlier this session) deliberately *overwrites* MDC's `correlationId` with the order ID once an order exists, for every downstream Kafka consumer. The browser's original `X-Correlation-Id` and the saga's `correlationId=<orderId>` are two different values for what a user experiences as one operation — contradicts ADR-0023's own stated intent ("a client may deliberately reuse the same correlation ID across several distinct calls that form one logical business operation (e.g., a checkout session spanning cart → order → payment calls) — reuse is expected, not a bug").
2. **No real distributed tracing exists.** Everything built so far (ADR-0023, `OrderCorrelationScope`) is text-log correlation via a shared ID string in log lines — there are no spans, no parent/child hierarchy, no latency breakdown per hop. ADR-0023 already flagged this as a known, deferred follow-up ("when Phase 7 adds Micrometer Tracing, align `X-Trace-Id` with the OTel-generated trace ID").
3. **No force-trace / verbose-logging control exists for support/debugging use**, and no user-facing Settings surface exists at all in the UI.
4. **No order-rate business metric exists** — Prometheus scrapes generic HTTP metrics only.

## Options Considered — correlation ID propagation

| Option | Pros | Cons |
|---|---|---|
| Keep current behavior (browser UUID → order ID switch) | No change needed | Directly fails the stated requirement; two IDs for one logical flow |
| Drop `OrderCorrelationScope`'s order-ID substitution; propagate the **original** correlation ID through every Kafka event **payload** instead | One ID, genuinely front-to-back, matches ADR-0023's original intent exactly | Every saga event record (`OrderCreatedEvent`, `InventoryReservedEvent`, `PaymentSuccessEvent`, etc.) needs a new `correlationId` field in its JSON schema, and every producer/consumer needs updating |
| Same, but as a **Kafka message header** instead of a payload field | Same front-to-back result, without touching any event's JSON schema or the `EventSchemaValidator` contract — correlation ID is transport metadata, not business data the event's own shape should carry | The value has to be captured at outbox-write time (same transaction as the business write) and persisted on the outbox row itself, since the poller that actually calls Kafka runs in a separate scheduled thread with no access to the original request's MDC context |

**Chosen (as implemented)**: the Kafka-header option, not payload — smaller blast radius (zero schema changes) for the same result. `OutboxEvent` gained a `correlationId` column (captured from MDC when the row is written); `OutboxPoller` attaches it as a `ProducerRecord` header when it actually publishes; every `@KafkaListener` reads it back via `@Header(required = false)` and passes it to `OrderCorrelationScope`, which now sets `correlationId` from that header (falling back to the order ID if absent, e.g. for a message published before this shipped) and `orderId` as a genuinely separate MDC field — order ID remains useful as its own always-logged, "find everything about order #23" key, but no longer *replaces* the correlation ID. Live-verified: a client-supplied correlation ID was confirmed identical in `order-service`'s `ORDER_CREATED` log line and `payment-service`'s `PAYMENT_SUCCESS` log line for the same order, two hops and one Kafka republish later.

## Options Considered — distributed tracing

| Option | Pros | Cons |
|---|---|---|
| Stay log-correlation-only (status quo) | Zero new infrastructure | Cannot show a request's hop-by-hop hierarchy or per-hop latency — the literal thing being asked for |
| Micrometer Tracing (OTel bridge) + Grafana **Tempo** as the trace backend, wired into the existing Loki+Grafana stack | Tempo is the natural pairing for a Loki/Grafana stack already running here (same vendor, native "trace ↔ log" linking in Grafana Explore), OTel auto-instruments Spring MVC/WebClient/Kafka with no per-endpoint code, `traceId` becomes real (W3C `traceparent`), closes ADR-0023's own documented follow-up | New component (Tempo) to deploy and operate; every service needs the tracing dependency + OTLP exporter config — comparable in size to the Loki/Alloy rollout already done this session |
| A hosted/SaaS APM (Datadog, Honeycomb, etc.) | Less self-hosted ops | Introduces an external dependency and cost for a project that has otherwise kept everything self-hosted in-cluster (Loki, Prometheus, Grafana) — inconsistent with every prior observability decision here |

**Chosen**: Micrometer Tracing + OTel + Tempo, self-hosted alongside Loki/Prometheus/Grafana.

## Options Considered — force-trace flag

| Option | Pros | Cons |
|---|---|---|
| No flag; always sample at a fixed rate | Simplest | No way to force full tracing on-demand while reproducing a specific user's issue — the actual support workflow this is meant to serve |
| A `canTrace` **role** gating a **`forceVerboseTracing`** Settings toggle (the flag itself named for what it does, not for the role that grants it) sends a request header the gateway/services read to force 100% sampling + elevated log detail for that session only | Self-service for support staff without needing a redeploy or config change; scoped to sessions with the role, not global | A session with this toggle on generates significantly more trace/log volume — acceptable since it's opt-in and role-gated, not a default |

**Chosen**: new realm role `CAN_TRACE`; Settings UI (under the existing user-info area in the header — user explicitly asked for it there, "like real time applications") with a "Force detailed tracing" toggle, visible only to `CAN_TRACE` sessions.

## Options Considered — sampling policy

| Option | Pros | Cons |
|---|---|---|
| Sample every request at a fixed rate, including errors | Simple, one number | Under sampling, a rare failure might never get exported — the opposite of what you want from tracing |
| **Parent-based sampling with an error override**: successful requests sampled at a configurable rate (default 10%); any request that ends in an error status is always exported regardless of the sampling decision; health-check endpoints excluded from the default sampler entirely (only exported on failure) | Never misses a failure trace, keeps steady-state volume low, matches exactly what was asked ("sampled for success and every failed request is logged, including health checks") | Requires an explicit sampler implementation, not just a flat ratio — real but standard OTel SDK capability |

**Chosen**: parent-based + error-always-sample + health-check-specific exclusion from the default sampler.

## Options Considered — order-rate metrics

| Option | Pros | Cons |
|---|---|---|
| Derive order rate from existing generic `http_server_requests` metric, filtered by path | No new code | Fragile (breaks if the route changes), can't distinguish a successful order from a 400/403 hitting the same path |
| A dedicated Micrometer `Counter` (`orders_created_total`), incremented once per successful `createOrder()`, plus a Grafana dashboard with `rate()` (orders/minute) and a time-of-day/day-of-week heatmap for peak-window analysis | Accurate, purpose-built, cheap (one counter, no new infrastructure — Prometheus already scrapes this service) | None significant |

**Chosen**: dedicated counter + Grafana panels.

## Decision

1. Add a `correlationId` field to every saga event record; producers copy the current MDC value onto it, consumers set MDC from it (not from order ID) via a renamed/adjusted `OrderCorrelationScope`. Order ID stays as a second, always-present log field for order-scoped searches, but no longer overwrites correlation ID.
2. Add Micrometer Tracing (OTel bridge) + OTLP export to every servlet service and the gateway; deploy Grafana Tempo in-cluster; wire Tempo as a Grafana datasource with trace↔log linking to the existing Loki datasource.
3. Add realm role `CAN_TRACE`; add a Settings entry under the header's user-info area with a `forceVerboseTracing` toggle, sent as a request header (`X-Force-Trace: true`) when enabled, honored by a custom OTel sampler that forces `RECORD_AND_SAMPLE` for that request's whole downstream call chain.
4. Sampling: `ParentBasedSampler` wrapping a ratio sampler (default 10%, configurable via `common-config`), with an always-sample override for (a) any response status >= 400, (b) `X-Force-Trace: true` present, (c) never applied as the *default* to `/actuator/health` (health-check spans excluded from the base sampler, still captured on failure via the error override).
5. Add `orders_created_total` Micrometer counter in `OrderService.createOrder()`; add a Grafana dashboard panel for orders/minute (`rate()`) and a peak-window heatmap (hour-of-day × day-of-week).

## Consequences

- Positive: closes ADR-0023's own documented follow-up; gives a genuine hop-by-hop, timed view of any request (the literal ask); keeps steady-state trace volume low while guaranteeing no failure is ever missed; gives support staff a self-service way to force full detail for one user's session without a redeploy.
- Negative / accepted trade-off: meaningfully larger observability footprint (new Tempo component, tracing dependency + config on every service, new realm role, new UI surface) — this is a real infrastructure addition, not a small patch, and is being implemented in stages rather than as one atomic change, with each stage live-verified before moving to the next.
- Follow-up required: if trace volume at the default 10% sample rate proves insufficient for a specific investigation, the rate is configurable via `common-config` without a code change; Tempo's local retention is not yet sized for long-term storage and should be revisited if this cluster needs to keep traces longer than a few days.

## Related

- Amends/closes the follow-up in ADR-0023 (Correlation ID and Trace ID): "align X-Trace-Id with the OTel-generated trace ID" is now implemented rather than deferred.
- `common-lib/src/main/java/org/bgm/common/correlation/OrderCorrelationScope.java`, `k8s/base/loki.yaml` (Tempo will follow the same Deployment pattern), `ui/src/index.html`/`app.js` (Settings UI).
