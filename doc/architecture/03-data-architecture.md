# 03 — Data Architecture (TOGAF ADM Phase C1)

## Principle

PostgreSQL, one logical schema per data-owning service, no cross-schema access — see ADR-0004 for the full evidence and rationale. A service that needs another service's data calls its API or consumes its published events; it never queries another service's schema.

## Schema Ownership

| Service | Schema | Core Tables (per Notes.md) |
|---|---|---|
| user-service | `user_service` | `users`, `roles`, `user_roles` |
| catalog-service | `catalog_service` | `product`, `discount`, `category` |
| inventory-service | `inventory_service` | `inventory` (`product_id`, `available_qty`, `reserved_qty`) |
| order-service | `order_service` | `orders`, `order_item` |
| payment-service | `payment_service` | `payment` |

`notification-service` is stateless with respect to durable business data (it dispatches from queue messages); it does not require its own schema unless a delivery-log/audit table is added, which would live under `notification_service` if introduced.

## Outbox Tables (ADR-0007)

Every schema belonging to a service that publishes Kafka events (`order_service`, `inventory_service`, `payment_service`) includes an `outbox_event` table (event ID, aggregate ID, event type, payload, published-at nullable timestamp). The business-data write and the outbox-row insert happen in the same local transaction; a poller reads unpublished rows, publishes to Kafka, and marks them published only after a broker ack — this is what makes event publishing atomic with the database write (closes the dual-write problem, ADR-0007). No service publishes directly from request-handling code without going through its own outbox.

## Migration Ownership

Each service owns its Flyway migration scripts under its own module (`src/main/resources/db/migration`), versioned and applied only against that service's schema on startup. No shared/global migration set.

## Cross-Service Data Access Rules

- Order Service does not read Inventory's or Payment's tables — it reacts to `inventory-reserved`/`payment-success`/`payment-failed` Kafka events (ADR-0003) and calls their REST APIs only for synchronous needs the event flow doesn't cover.
- User identity: Keycloak owns credentials; `user_service.users` stores only profile data linked to the Keycloak subject ID (ADR-0001) — no password hashes are duplicated into the application database.

## Secrets

Database connection credentials are Kubernetes Secrets from Phase 7 of the delivery plan onward (`doc/architecture/07-migration-planning.md`) — the audited hardcoded `root/root` MySQL credentials in the current config-server drafts are the specific anti-pattern this closes.

## Related

- ADR-0004 (Postgres, schema-per-service — full rationale and evidence)
- ADR-0007 (Saga + Outbox — why the outbox tables above exist)
- `doc/architecture/02-business-architecture.md` for the flows that generate this data
