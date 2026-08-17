# ADR-0059: Fix InventorySagaConsumer's silent-success logging gap

**Status**: Accepted
**Date**: 2026-08-17 16:35 IST
**Deciders**: Solution/Security Architect

## Context

Flagged as a known, deferred gap back in ADR-0054's Consequences section: `InventorySagaConsumer` had zero log output on its success paths — `onOrderCreated`'s reservation success and `onPaymentFailed`'s compensating release both ran silently, with only the failure branch (`log.info("Reservation failed for order {}: {}", ...)`) ever producing a log line. The only prior evidence a reservation or release succeeded was the `outbox_event` database row — invisible to any Loki-based investigation, the same class of gap ADR-0054 already fixed for `notification-service`.

## Decision

Added `AuditLogger` calls on both success paths, matching the existing convention used by `PaymentSagaConsumer` (`PAYMENT_SUCCESS`/`PAYMENT_FAILED`) and `OrderService` (`ORDER_CREATED`):
- `onOrderCreated`'s success branch: `AuditLogger.log("INVENTORY_RESERVED", ...)` with `orderId` and `itemCount`.
- `onPaymentFailed`'s release branch: `AuditLogger.log("INVENTORY_RELEASED", ...)` with `orderId` and `itemCount`.

Both run inside the existing `OrderCorrelationScope`, so — combined with ADR-0056/0057's fixes — these lines carry correct `correlationId`/`orderId`/`appTraceId` and (once a Kafka listener span exists) correct span attributes, same as every other saga consumer's audit lines.

## Regression guard

- `./mvnw -pl inventory-service -am test`: `BUILD SUCCESS`, no regressions (only the existing context-load smoke test exists for this module — no saga-consumer unit tests existed before this session for any service, a pre-existing gap not introduced or fully closed here).
- No new unit test added for this specific change — kept intentionally small per explicit instruction to address this as the quick, low-risk item; verification is via live redeploy + Loki query (a real order's `INVENTORY_RESERVED` line should now appear), not a new mock-heavy test file.

## Consequences

- Positive: closes the last of the three "silent success" gaps identified across this session's investigation (notification-service fixed in ADR-0054, order-service's own paths were already covered, inventory-service closed here).
- Neutral: does not change trace-ID/span-attribute behavior — this is purely adding the missing log statements at points where correlation context was already correctly available.

## Related

- ADR-0054: the notification-service fix that first identified this class of gap and explicitly deferred the inventory-service instance of it.
- ADR-0056/0057: the mechanisms (`OrderCorrelationScope` attribute stamping, Kafka listener observation) that make these new log lines' correlation context meaningful once a Kafka consumer span genuinely exists.
