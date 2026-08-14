package org.bgm.common.event.schema;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the ADR-0015 enforcement mechanism actually works: a valid
 * order-created event passes, and the exact kind of breaking change
 * described in ADR-0015's evidence (a renamed required field) is rejected
 * with a real error, not silently accepted.
 */
class EventSchemaValidatorTest {

    private final EventSchemaValidator validator = new EventSchemaValidator();

    @Test
    void validOrderCreatedEventPasses() {
        String valid = """
            {
              "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
              "orderId": 42,
              "customerId": "cust-123",
              "items": [
                {"productId": 7, "quantity": 2, "unitPrice": 19.99}
              ],
              "totalAmount": 39.98,
              "occurredAt": "2026-08-13T10:15:30Z"
            }
            """;

        validator.validate(EventType.ORDER_CREATED, valid); // must not throw
    }

    @Test
    void renamedRequiredFieldIsRejected_theExactScenarioADR0015Describes() {
        // "orderId" renamed to "order_id" — the precise breaking-change
        // scenario walked through when ADR-0015 was decided.
        String brokenByRename = """
            {
              "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
              "order_id": 42,
              "customerId": "cust-123",
              "items": [
                {"productId": 7, "quantity": 2, "unitPrice": 19.99}
              ],
              "totalAmount": 39.98,
              "occurredAt": "2026-08-13T10:15:30Z"
            }
            """;

        EventSchemaValidationException ex = assertThrows(
                EventSchemaValidationException.class,
                () -> validator.validate(EventType.ORDER_CREATED, brokenByRename)
        );

        assertEquals(EventType.ORDER_CREATED, ex.eventType());
        assertFalse(ex.validationErrors().isEmpty());
    }

    @Test
    void wrongTypeIsRejected() {
        String wrongType = """
            {
              "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
              "orderId": "not-a-number",
              "customerId": "cust-123",
              "items": [
                {"productId": 7, "quantity": 2, "unitPrice": 19.99}
              ],
              "totalAmount": 39.98,
              "occurredAt": "2026-08-13T10:15:30Z"
            }
            """;

        assertThrows(EventSchemaValidationException.class,
                () -> validator.validate(EventType.ORDER_CREATED, wrongType));
    }

    @Test
    void missingRequiredItemsArrayIsRejected() {
        String missingItems = """
            {
              "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
              "orderId": 42,
              "customerId": "cust-123",
              "totalAmount": 39.98,
              "occurredAt": "2026-08-13T10:15:30Z"
            }
            """;

        assertThrows(EventSchemaValidationException.class,
                () -> validator.validate(EventType.ORDER_CREATED, missingItems));
    }

    @Test
    void allSevenEventSchemasLoadAndValidateAMinimalValidPayload() {
        assertTrue(EventType.values().length == 8, "expected 8 event types incl. inventory-reservation-failed");

        record Case(EventType type, String json) {}
        var cases = java.util.List.of(
                new Case(EventType.ORDER_CANCELLED, """
                    {"eventId":"3fa85f64-5717-4562-b3fc-2c963f66afa6","orderId":1,"reason":"customer request","occurredAt":"2026-08-13T10:15:30Z"}
                    """),
                new Case(EventType.ORDER_RETURNED, """
                    {"eventId":"3fa85f64-5717-4562-b3fc-2c963f66afa6","orderId":1,"items":[{"productId":7,"quantity":1}],"occurredAt":"2026-08-13T10:15:30Z"}
                    """),
                new Case(EventType.PAYMENT_SUCCESS, """
                    {"eventId":"3fa85f64-5717-4562-b3fc-2c963f66afa6","orderId":1,"paymentId":"pay-1","amount":39.98,"occurredAt":"2026-08-13T10:15:30Z"}
                    """),
                new Case(EventType.PAYMENT_FAILED, """
                    {"eventId":"3fa85f64-5717-4562-b3fc-2c963f66afa6","orderId":1,"reason":"card declined","occurredAt":"2026-08-13T10:15:30Z"}
                    """),
                new Case(EventType.INVENTORY_RESERVED, """
                    {"eventId":"3fa85f64-5717-4562-b3fc-2c963f66afa6","orderId":1,"items":[{"productId":7,"quantity":1}],"occurredAt":"2026-08-13T10:15:30Z"}
                    """),
                new Case(EventType.INVENTORY_RESERVATION_FAILED, """
                    {"eventId":"3fa85f64-5717-4562-b3fc-2c963f66afa6","orderId":1,"reason":"out of stock","occurredAt":"2026-08-13T10:15:30Z"}
                    """),
                new Case(EventType.INVENTORY_RELEASED, """
                    {"eventId":"3fa85f64-5717-4562-b3fc-2c963f66afa6","orderId":1,"items":[{"productId":7,"quantity":1}],"occurredAt":"2026-08-13T10:15:30Z"}
                    """)
        );

        for (Case c : cases) {
            validator.validate(c.type(), c.json()); // must not throw for any of them
        }
    }
}
