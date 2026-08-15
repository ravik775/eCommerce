-- ADR-0032: the original request's correlation ID, captured at the same
-- moment (same transaction) the outbox row is written — the poller that
-- actually publishes to Kafka runs in a separate scheduled thread with
-- no access to the original request's MDC context, so the value has to
-- be persisted here to survive that hop.
ALTER TABLE outbox_event ADD COLUMN correlation_id VARCHAR(255);
