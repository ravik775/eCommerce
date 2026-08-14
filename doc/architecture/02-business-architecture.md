# 02 — Business Architecture (TOGAF ADM Phase B)

## Actors and Roles

| Role | Capabilities |
|---|---|
| `CUSTOMER` | Register/login (via Google), browse catalog, place/cancel/return orders, view own order history |
| `ADMIN` | All `CUSTOMER` capabilities + product/catalog management (create/update/delete products, manage discounts) |
| `SUPER_ADMIN` | All `ADMIN` capabilities + user/role management |

Roles are defined and enforced in Keycloak (ADR-0001) and asserted as JWT claims validated by every backend service (ADR-0005), not just the gateway.

## Core Business Flows

### Order Placement — Choreography Saga (ADR-0007)

This flow is a choreography-based Saga: no central coordinator, each service reacts to the previous step's event. Every publish below goes through that service's transactional outbox (ADR-0007), never a direct publish from request-handling code, and every consumer is idempotent against redelivery.

1. Customer places an order (Order Service) — order row + outbox row written in one local transaction.
2. Outbox poller publishes `order-created` (Kafka).
3. Inventory Service consumes `order-created` (idempotently, keyed by `order_id`), reserves stock, and publishes `inventory-reserved` — or, if reservation fails, publishes `inventory-reservation-failed`.
4. Payment Service consumes `inventory-reserved`, charges the customer, publishes `payment-success` or `payment-failed`.
5. Order Service consumes the payment outcome and updates order status.
6. Notification Service consumes the terminal event and dispatches a confirmation/failure notification via the RabbitMQ `notification.dispatch` queue.

**Compensating actions (saga rollback)**:
- `inventory-reservation-failed` → Order Service moves the order to a failed/cancelled state; no payment is attempted.
- `payment-failed` (after inventory was reserved) → Inventory Service consumes it and **releases** the previously reserved stock — the compensating action that undoes step 3.

Order status lifecycle (per Notes.md): `CREATED → PAYMENT_PENDING → PAYMENT_COMPLETED → PROCESSING → SHIPPED → DELIVERED`, with `CANCELLED` and `RETURNED` as alternate terminal/branch states.

### Order Cancellation
Order Service publishes `order-cancelled` (via outbox); Inventory Service consumes it idempotently and releases previously reserved stock — a customer-initiated compensating action, structurally the same as the payment-failure compensation above.

### Order Return
Order Service publishes `order-returned` (via outbox); Inventory Service consumes it idempotently and adds stock back.

### Product/Catalog Management (Admin)
`ADMIN` manages product lifecycle (create/update/price/discount/deactivate) directly via Catalog Service's REST API, gated by the `ADMIN` role claim.

## Business Rules Captured for Architecture (not exhaustive product rules)

- A service never reaches into another service's data directly — cross-service data needs are satisfied via API calls (synchronous) or consumed events (asynchronous), per ADR-0004.
- Money-movement actions (order placed, payment processed/refunded) are audit-logged distinctly from general application logs, per the SOC2-alignment principle in `00-preliminary.md`.

## Related

- `doc/architecture/04-application-architecture.md` for the service-level implementation of these flows
- ADR-0003 (eventing), ADR-0004 (data ownership), ADR-0007 (saga/outbox/idempotency — full rationale for the pattern used above)
