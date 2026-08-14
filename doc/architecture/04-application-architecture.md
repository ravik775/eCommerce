# 04 — Application Architecture (TOGAF ADM Phase C2)

## Service Inventory

| Service | Current State (as audited) | Target Responsibility |
|---|---|---|
| `api-gateway` | Routing/rate-limiting/security config drafted but inert; `bootstrap.yml` bug fixed | Edge routing, OIDC login termination, JWT relay, edge rate limiting (ADR-0005) |
| `service-discovery` | Minimal working Eureka server | Service registry, unchanged |
| `config-server` | Native/classpath config source; three redundant gateway drafts | Centralized config; consolidated to one canonical gateway config (ADR-0005) |
| `user-service` | Skeleton only | Registration/profile linkage to Keycloak identity, role assignment |
| `catalog-service` | Skeleton only | Product/category/discount CRUD, search |
| `inventory-service` | Skeleton only | Stock reservation/release/add, reacts to order events |
| `order-service` | Partial: entities/repos exist, all business methods stubbed | Order lifecycle orchestration, publishes/consumes order-related events |
| `payment-service` | Skeleton only | Payment initiation/confirmation/refund, gateway adapter strategy pattern (per Notes.md) |
| `notification-service` | Skeleton only | Consumes Kafka terminal events, dispatches via RabbitMQ work queue |
| `common-lib` | Minimal | Shared DTOs/utilities **plus** the new `spiffe-mtls` module (ADR-0002) |

## API Contracts

Per-service REST APIs as specified in Notes.md (`/products`, `/orders`, `/inventory/*`, `/payments`, `/users/*`) are the contract baseline; no endpoint is considered implemented until it returns real data/errors, not a stub 503 (see DoD checklist in `07-migration-planning.md`).

## Call Graph (drives NetworkPolicy allow-lists, ADR-0006)

```
Browser/UI -> API Gateway -> {user, catalog, order}-service
order-service -> inventory-service, payment-service (sync, Resilience4j-guarded)
order-service, inventory-service, payment-service -> Kafka (produce/consume)
notification-service -> Kafka (consume), RabbitMQ (produce)
payment-service -> RabbitMQ (produce, retry queue)
all services -> Postgres (own schema only)
all services -> Eureka, Config Server
all services -> SPIRE Workload API (via common-lib spiffe-mtls)
api-gateway, all services -> Keycloak (token validation / JWKS)
```

## Module Boundaries

Package structure follows the convention already established in Notes.md's "Production Package Structure Example" (`controller/service/repository/entity/dto/mapper/client/event/listener/producer/consumer/exception/security/validator/util`), applied consistently across all services rather than only order-service.

## Related

- ADR-0002 (`spiffe-mtls` in common-lib), ADR-0003 (eventing), ADR-0005 (gateway boundary)
- `doc/architecture/06-opportunities-solutions.md` for build-vs-reuse decisions per module
