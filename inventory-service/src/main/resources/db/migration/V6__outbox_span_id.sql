-- ADR-0062: see order-service's V6 migration for the full rationale —
-- this service forwards the value unchanged, never generates its own.
ALTER TABLE outbox_event ADD COLUMN span_id VARCHAR(32);
