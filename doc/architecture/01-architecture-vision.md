# 01 — Architecture Vision (TOGAF ADM Phase A)

## Problem Statement

The repository is a Maven multi-module skeleton for an event-driven eCommerce platform. Audit findings (captured in full in the delivery plan, `doc/architecture/07-migration-planning.md`) show it is materially earlier-stage than its own design docs suggest: only `order-service` has partial scaffolding, five of ten modules are empty shells not even wired into the build, security is present as dependencies but disabled (`permitAll()`), and there is no containerization, orchestration, real datastore, or UI. The vision below defines what "done" looks like so the gap is closed deliberately, not accidentally.

## Stakeholders

| Stakeholder | Concern |
|---|---|
| Solution/Security Architect (this engagement's driver) | Correctness of architecture decisions, zero-trust posture, SOC2 alignment, avoiding unnecessary complexity |
| Customers (end users) | Can sign up/log in with Google, browse catalog, check out reliably, low-latency responses |
| Platform/Ops (future team running this in K8s) | Deployability, observability, ability to diagnose incidents, manageable operational surface |
| Compliance (SOC2-oriented) | Auditable auth and money-movement events, no plaintext secrets, least-privilege access |

## Target State Summary

A platform where:
- Every documented service (user, catalog, inventory, order, payment, notification) has real business logic backed by PostgreSQL, one schema per service (ADR-0004).
- Users authenticate via Google through Keycloak-issued OIDC (ADR-0001); every service independently validates the resulting JWT (ADR-0005) — no implicit trust based on request origin.
- Cross-service calls are protected by SPIFFE-issued mTLS enforced at the application layer (ADR-0002), plus Kubernetes NetworkPolicies as an independent network-layer control (ADR-0006), inside a dedicated `ecom` namespace.
- Order/payment/inventory events flow through Kafka; point-to-point reliable work (notification dispatch, payment-gateway retries) flows through RabbitMQ (ADR-0003).
- The platform runs first via Docker Compose for local development, then on Kubernetes for production-representative deployment, with observability (Prometheus/Grafana), rate limiting, and audit logging in place before being considered complete.
- A minimal UI lets a real user complete the full login → browse → checkout journey.

## Success Criteria

Defined operationally, not aspirationally — each item below is a Definition-of-Done checklist item in `doc/architecture/07-migration-planning.md`, verified by running the system, not just reading the code.

## Related

- ADR-0001, ADR-0002, ADR-0003, ADR-0004, ADR-0005, ADR-0006
- `doc/architecture/07-migration-planning.md` for the phased path to this target state
