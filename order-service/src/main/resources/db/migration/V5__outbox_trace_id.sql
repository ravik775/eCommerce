-- ADR-0052: same mechanism as V4's correlation_id column, for the
-- gateway-generated X-Trace-Id — captured at the same moment (same
-- transaction) the outbox row is written, so it survives the hop to the
-- OutboxPoller's separate scheduled thread and onward across Kafka.
ALTER TABLE outbox_event ADD COLUMN trace_id VARCHAR(255);
