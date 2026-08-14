package org.bgm.common.event.schema;

import java.util.Set;

/**
 * Thrown when a Kafka event payload fails validation against its JSON
 * Schema (ADR-0015). Producers throw this before writing to the outbox
 * table (ADR-0007) — an invalid event never reaches Kafka. Consumers throw
 * this on receipt as a second, independent check.
 */
public class EventSchemaValidationException extends RuntimeException {

    private final EventType eventType;
    private final Set<String> validationErrors;

    public EventSchemaValidationException(EventType eventType, Set<String> validationErrors) {
        super("Event '" + eventType.topic() + "' failed schema validation: " + validationErrors);
        this.eventType = eventType;
        this.validationErrors = validationErrors;
    }

    public EventType eventType() {
        return eventType;
    }

    public Set<String> validationErrors() {
        return validationErrors;
    }
}
