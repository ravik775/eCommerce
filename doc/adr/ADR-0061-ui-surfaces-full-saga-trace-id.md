# ADR-0061: UI shows the full appTraceId as the authoritative saga search key

**Status**: Accepted
**Date**: 2026-08-17 17:35 IST
**Deciders**: Solution/Security Architect

## Context

Reported live: the checkout success message ("Order #82 placed (trace ref: e58e539c) — payment processing.") was "not useful information to search in Grafana." Investigated and confirmed: `trace ref` was the first 8 characters of `X-Correlation-Id`, truncated by the UI's own `shortRef()` helper specifically for display. This value fails an exact-match Tempo/Loki search outright (proven live against a real correlationId prefix), and even the full `X-Correlation-Id` was never the right value to search with in the first place — it's a per-call idempotency-style reference, not the cross-saga identifier.

Deeper analysis (see the same investigation) established that `appTraceId` is the one identifier this system has consistently, verifiably kept identical across every hop of a saga (Loki logs and `outbox_event.trace_id` DB rows agree, order after order, all session) — the genuinely authoritative cross-saga correlation ID, as opposed to Tempo's own per-hop trace IDs, which this same investigation proved are **not** linked into one tree across the choreography saga's Kafka boundaries (a separate, structural limitation of the outbox pattern, not fixable by a UI change).

Also confirmed by reading `CorrelationTraceFilter`: the `X-Trace-Id` response header **already carries this exact `appTraceId` value** for every servlet backend — no backend change was needed, only fixing what the UI does with a value it already receives.

## Decision

- Added `traceIdFrom(res)` reading `X-Trace-Id` from the checkout response (already the `appTraceId` value).
- Replaced the truncated "trace ref" text with `renderSagaIdNotice()`: full, untruncated value, labeled **"Saga ID (Trace ID)"**, in a `<code>` element, with a Copy button (`navigator.clipboard`) and a line pointing at the Order Trace Explorer dashboard (ADR-0061's companion piece from earlier this session) as where to paste it.
- `X-Correlation-Id` (`refFrom`) is untouched and still used for error messages, where a full-length, immediately-pasteable value was already correct — only the success-path truncation is removed.

## Regression guard

- `node -c ui/src/app.js`: syntax valid.
- No existing test suite for `ui/` (a static-file, no-build-step app per its own Dockerfile comment) — verification is live: place an order, confirm the displayed Saga ID matches the real `appTraceId` found in Loki for that order, and that pasting it into the Order Trace Explorer dashboard's search box returns real results.

## Consequences

- Positive: closes the exact "not useful information to search" gap reported live — the value shown is now the one genuinely worth searching with, in full, with a one-click copy.
- Positive: reinforces the session's broader finding (`appTraceId` as the authoritative cross-saga ID, Tempo's own trace IDs as a separate, per-hop-only tool) directly in the product surface, not just internal documentation.

## Related

- ADR-0023: the original correlationId/traceId distinction this fix respects rather than blurs.
- ADR-0052/0056: the mechanisms that make appTraceId consistently available to surface here.
- The Order Trace Explorer dashboard (this session, k8s/base/grafana.yaml): where the copied Saga ID is meant to be pasted.

### 2026-08-17 17:55 IST update — dashboard-side gap closed too (Option 1)

Follow-up review of the Order Trace Explorer dashboard found it didn't fully deliver on this ADR's own premise: its `$search` variable treated `orderId` and `appTraceId` as symmetric, equal-weight alternatives (`span.orderId="$search" || span.appTraceId="$search"`) — searching by order ID never surfaced the corresponding `appTraceId` back to the user, and nothing framed `appTraceId` as *the* authoritative identifier the way the UI now does (this ADR's original decision) — the exact same friction this ADR fixed in the checkout UI was still present one click away, in the dashboard meant to be its destination.

**Fix** (`k8s/base/grafana.yaml`): added a new top panel, "Resolved Saga ID (appTraceId)" — a Loki query for the first log line matching `$search`, run through Grafana's built-in **Extract fields** transformation (regexp source, `appTraceId=(?<appTraceId>[a-f0-9-]+)` and `orderId=(?<orderId>[0-9]+)`) into a two-column table. No new datasource or plugin — reuses the Loki datasource already wired up. Whatever you searched with, this panel now resolves and displays the canonical Saga ID.

Verified live: the extraction regex was tested directly against a real captured log line for order 82 and correctly produced `ecfbd4b1-8aed-4fbb-a4ed-c12289b39282` — the same value independently confirmed via Loki as that order's `appTraceId`. Dashboard confirmed provisioned without error via Grafana's own API (`GET /api/search`, `GET /api/dashboards/uid/...`) after redeploy, all 7 panels present.
