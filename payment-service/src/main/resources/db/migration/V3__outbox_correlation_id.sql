-- ADR-0032: see order-service's identical migration for the full
-- rationale — captured at write time, survives the hop to the poller's
-- separate scheduled thread.
ALTER TABLE outbox_event ADD COLUMN correlation_id VARCHAR(255);
