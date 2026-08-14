-- ADR-0007: processed-event dedupe table, notification_service schema.

CREATE TABLE processed_event (
    event_id      VARCHAR(64) PRIMARY KEY,
    processed_at  TIMESTAMP NOT NULL
);
