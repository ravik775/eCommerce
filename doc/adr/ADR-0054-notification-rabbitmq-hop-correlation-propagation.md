# ADR-0054: Propagate correlation context across the notification-service RabbitMQ hop

**Status**: Accepted
**Date**: 2026-08-17 12:10 IST
**Deciders**: Solution/Security Architect

## Context

Debugging live order 66's trace found that `NotificationDispatchWorker`'s "Dispatched notification" log line always showed blank `correlationId`/`orderId`/`appTraceId`, even after ADR-0052 (end-to-end trace-ID propagation) and ADR-0053 (span-attribute enrichment) both landed and were confirmed working correctly for order-service, inventory-service, and payment-service (verified via `outbox_event.trace_id` ground truth — identical value across all three).

Root cause, confirmed by reading the code: `NotificationEventConsumer.onPaymentSuccess`/`onPaymentFailed` (the Kafka consumer) correctly enter `OrderCorrelationScope` and populate MDC — but the actual "send it" work is handed off via `rabbitTemplate.convertAndSend(...)` to `NotificationDispatchWorker`, an `@RabbitListener` that runs on a **separate, RabbitMQ-managed thread**. MDC is thread-local; nothing carried the correlation values across that hop, so `NotificationDispatchWorker`'s own log line was permanently blank on those fields regardless of whether upstream propagation worked — a distinct, pre-existing gap, not a regression introduced by ADR-0052/0053.

Separately, `NotificationEventConsumer` itself had zero log statement on its own success path (only `InventorySagaConsumer` had this same gap, on its failure path only) — meaning there was no log evidence at all that a notification had been accepted for dispatch, only evidence (or its absence) of the dispatch worker's own outcome.

## Decision

**1. Carry correlation context across the RabbitMQ hop as message headers** — the same fix ADR-0052 already applied to the Kafka hop (`OutboxPoller` → `RecordHeader`), reused here rather than inventing a new mechanism:
- `NotificationEventConsumer.dispatch(...)` reads `correlationId`/`orderId`/`appTraceId` from the current MDC (valid at this call site, since it runs inside `OrderCorrelationScope`) and sets them as RabbitMQ message headers via a `MessagePostProcessor`, using `CorrelationConstants`' existing key names (the same constants already dual-purposed as both MDC keys and Kafka header names).
- `NotificationDispatchWorker.handle(...)` gained `@Header`-annotated parameters for `correlationId`/`traceId`, and now wraps its body in `OrderCorrelationScope.forOrder(message.orderId(), correlationId, traceId)` — the exact same try-with-resources pattern every other saga consumer in this codebase already uses, so its "Dispatched notification" log line (and any future log line added there) is correctly tagged, and MDC is guaranteed cleaned up on this pooled thread even if dispatch throws (verified by test — see below).

**2. Added an `AuditLogger` line in `NotificationEventConsumer`** (`NOTIFICATION_DISPATCH_QUEUED`, with `orderId`/`type` fields) on the success path of both `onPaymentSuccess` and `onPaymentFailed` — closes the same "silent success" gap `InventorySagaConsumer` still has (out of scope for this ADR; noted as a candidate follow-up, not fixed here since it wasn't the reported issue).

## Regression guard

- New `NotificationEventConsumerTest` (3 cases): `dispatch()` carries `correlationId`/`orderId`/`appTraceId` as RabbitMQ message headers for both `onPaymentSuccess` and `onPaymentFailed`; a missing `traceId` degrades to the header simply being omitted (matching `OrderCorrelationScope`'s own documented degrade-instead-of-blank behavior), not a placeholder value. Uses a small `RabbitTemplate` subclass instead of a Mockito mock — `RabbitTemplate` could not be mocked on this machine's JDK (Mockito's inline mock-maker failed to instrument it, a JDK-version compatibility issue, not a code problem).
- New `NotificationDispatchWorkerTest` (3 cases), using a Logback `ListAppender` to capture each log event's own MDC snapshot (the reliable way to assert "MDC held this value at the moment this line logged", independent of pattern-layout config): headers are correctly restored into MDC before logging; missing headers degrade gracefully (orderId always present since it's `OrderCorrelationScope.forOrder`'s own parameter, correlationId falls back to the order ID, traceId stays genuinely unset); MDC is fully cleared after handling even when the listener throws (the existing negative-orderId test hook).
- Full multi-module suite (`./mvnw test`, all 11 modules): **BUILD SUCCESS**, no regressions — including `notification-service`'s own suite (7 tests: the 6 new cases plus the existing context-load smoke test).

## Consequences

- Positive: closes the actual reported gap — `NotificationDispatchWorker`'s log line, and any future logging added inside its `handle()` method, is now correctly correlated, completing the saga's log trail all the way through the one hop that was still cold.
- Positive: reuses existing patterns exactly (`CorrelationConstants`, `OrderCorrelationScope`, the `MessagePostProcessor` approach mirrors `OutboxPoller`'s `RecordHeader` approach) — no new propagation mechanism, no new constants file, nothing for a future reader to learn beyond what ADR-0052 already established.
- Negative / accepted scope limit: `InventorySagaConsumer`'s equivalent silent-success gap (no log line at all on its happy path) was found during this same investigation but is **not** fixed here — flagged as a candidate follow-up, since it wasn't the reported issue and fixing it doesn't require any of this ADR's propagation mechanism (the Kafka consumer thread already has correct MDC; it just never logs).

## Related

- ADR-0007: choreography saga / transactional outbox pattern.
- ADR-0052: gateway-generated `X-Trace-Id`, end-to-end propagation via outbox/Kafka headers — the mechanism this ADR extends across the one remaining hop (RabbitMQ) that ADR-0052 didn't cover.
- ADR-0053: span-attribute enrichment — the sibling investigation that first surfaced this gap while confirming ADR-0052's fix was genuinely deployed and working, via `outbox_event.trace_id` ground truth.
