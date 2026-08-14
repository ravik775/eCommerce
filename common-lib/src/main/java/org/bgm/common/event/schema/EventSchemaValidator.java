package org.bgm.common.event.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Validates Kafka event payloads against the checked-in JSON Schema for
 * their type (ADR-0015: doc/adr/ADR-0015-kafka-schema-json-documented.md).
 *
 * This is the concrete enforcement mechanism for that ADR's decision to
 * skip a standalone schema registry: schemas live in this jar (shared by
 * every service via common-lib) instead of a separate stateful service,
 * and are checked at publish time (before the outbox write, ADR-0007) and
 * again at consume time (defense in depth against schema drift between
 * service deployments).
 *
 * Thread-safe: schemas are compiled once and cached.
 */
public class EventSchemaValidator {

    private final ObjectMapper objectMapper;
    private final JsonSchemaFactory schemaFactory;
    private final Map<EventType, JsonSchema> schemaCache = new ConcurrentHashMap<>();

    public EventSchemaValidator() {
        this(new ObjectMapper());
    }

    public EventSchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    }

    /**
     * Validates a raw JSON payload against its event type's schema.
     *
     * @throws EventSchemaValidationException if the payload does not conform
     * @throws IllegalArgumentException        if the payload is not valid JSON
     */
    public void validate(EventType eventType, String jsonPayload) {
        JsonNode node;
        try {
            node = objectMapper.readTree(jsonPayload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Payload for '" + eventType.topic() + "' is not valid JSON", e);
        }
        validate(eventType, node);
    }

    public void validate(EventType eventType, JsonNode payload) {
        JsonSchema schema = schemaFor(eventType);
        Set<ValidationMessage> errors = schema.validate(payload);
        if (!errors.isEmpty()) {
            Set<String> messages = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            throw new EventSchemaValidationException(eventType, messages);
        }
    }

    /** Returns validation errors without throwing — for callers that want to log/report rather than fail fast. */
    public Set<String> validationErrors(EventType eventType, JsonNode payload) {
        return schemaFor(eventType).validate(payload).stream()
                .map(ValidationMessage::getMessage)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private JsonSchema schemaFor(EventType eventType) {
        return schemaCache.computeIfAbsent(eventType, this::loadSchema);
    }

    private JsonSchema loadSchema(EventType eventType) {
        String path = eventType.schemaResourcePath();
        try (InputStream in = EventSchemaValidator.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing schema resource on classpath: " + path);
            }
            SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().build();
            return schemaFactory.getSchema(in, config);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException("Failed reading schema resource: " + path, e);
        }
    }
}
