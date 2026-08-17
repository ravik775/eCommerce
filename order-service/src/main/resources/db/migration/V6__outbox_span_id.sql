-- ADR-0062: the real OTel span ID of the request that created this row —
-- order-service is the saga's root, so this is a fresh live capture,
-- carried forward unchanged by every downstream outbox_event.span_id
-- column (inventory-service, payment-service) for span-link creation
-- on the consuming side.
ALTER TABLE outbox_event ADD COLUMN span_id VARCHAR(32);
