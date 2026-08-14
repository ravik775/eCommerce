package org.bgm.common.event.schema;

/**
 * Kafka domain events per ADR-0003 (doc/adr/ADR-0003-eventing-kafka-rabbitmq.md)
 * and ADR-0007 (doc/adr/ADR-0007-saga-outbox-idempotency.md). Each maps to a
 * JSON Schema file under common-lib/src/main/resources/schemas/, which is
 * how ADR-0015 (doc/adr/ADR-0015-kafka-schema-json-documented.md) enforces
 * event shape without a separate schema registry service.
 */
public enum EventType {
    ORDER_CREATED("order-created"),
    ORDER_CANCELLED("order-cancelled"),
    ORDER_RETURNED("order-returned"),
    PAYMENT_SUCCESS("payment-success"),
    PAYMENT_FAILED("payment-failed"),
    INVENTORY_RESERVED("inventory-reserved"),
    INVENTORY_RESERVATION_FAILED("inventory-reservation-failed"),
    INVENTORY_RELEASED("inventory-released");

    private final String topic;

    EventType(String topic) {
        this.topic = topic;
    }

    public String topic() {
        return topic;
    }

    public String schemaResourcePath() {
        return "/schemas/" + topic + ".schema.json";
    }
}
