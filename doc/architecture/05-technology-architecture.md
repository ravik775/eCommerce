# 05 — Technology Architecture (TOGAF ADM Phase D)

## Runtime Stack

| Layer | Technology | Decision Reference |
|---|---|---|
| Language/Runtime | Java 21, Spring Boot 3.5 | existing repo baseline |
| API Gateway | Spring Cloud Gateway | existing repo baseline, config finalized per ADR-0005 |
| Service Discovery | Eureka | existing repo baseline |
| Config Management | Spring Cloud Config Server | existing repo baseline |
| Resilience | Resilience4j (circuit breaker, retry, bulkhead) | existing repo baseline, actually wired to code in delivery Phase 2 |
| Identity Provider | Keycloak, Google as federated IdP | ADR-0001 |
| Zero-trust identity | SPIRE (server + agent), consumed via `common-lib` `spiffe-mtls` module — **no service mesh** | ADR-0002 |
| Datastore | PostgreSQL, schema-per-service | ADR-0004 |
| Domain eventing | Kafka | ADR-0003 |
| Task queues | RabbitMQ | ADR-0003 |
| Rate limiting | Redis (token bucket, at the gateway — required because the gateway runs multiple replicas) | ADR-0005, ADR-0009 |
| Observability | Micrometer + Prometheus + Grafana, OpenTelemetry tracing | delivery Phase 7 |
| Containerization | Docker (multi-stage builds, non-root) | delivery Phase 5 |
| Orchestration | Kubernetes, namespace `ecom` | delivery Phase 6 |
| Network security | Kubernetes NetworkPolicy, default-deny + explicit allow | ADR-0006 |

## Deployment Topology

```
Local dev:      mvn / IDE, per-service application-local.yml, optional Testcontainers
                 |
Docker Compose:  all 8 app services + Postgres + Kafka + RabbitMQ + Keycloak
                 + Redis + Eureka + Config Server, one bridge network
                 |
Kubernetes:      namespace "ecom"
                 - Deployments (gateway: multiple replicas) + Services per component
                 - Ingress: api-gateway only
                 - Service discovery: Kubernetes Service DNS (no Eureka — ADR-0008)
                 - Config: ConfigMaps/Secrets via spring-cloud-kubernetes-config
                   (no Config Server — ADR-0008)
                 - NetworkPolicy: default-deny + explicit allow (ADR-0006)
                 - SPIRE server (Deployment) + SPIRE agent (DaemonSet)
                 - Postgres: StatefulSet+PVC or operator (open decision, see
                   doc/architecture/07-migration-planning.md)
```

**Environment parity note (revised per ADR-0008)**: Compose and Kubernetes are *behaviorally* parallel (the same request flows produce the same outcomes) but deliberately diverge on discovery/config *mechanism* — Compose uses Eureka + Config Server (no native equivalent exists in plain Compose), Kubernetes uses its own Service DNS and ConfigMaps/Secrets. This divergence is intentional and tracked, not an inconsistency to "fix" — see ADR-0008 for the full reasoning. Each service therefore carries a Compose-profile and a Kubernetes-profile discovery/config client, both exercised in CI (`doc/architecture/10-development-testing-deployment.md`).

## Why No Service Mesh (summary — full evidence in ADR-0002)

SPIRE issues SPIFFE identity; each service enforces mTLS on itself via a shared library, not a sidecar or mesh control plane. This was a **researched reversal** from an initial Linkerd+SPIRE direction — kept visible here rather than silently corrected, per the "evidence over instinct" principle in `00-preliminary.md`.

## Related

- ADR-0001 through ADR-0006 (all referenced above)
- `doc/architecture/10-development-testing-deployment.md` for how this stack is exercised through CI/CD
