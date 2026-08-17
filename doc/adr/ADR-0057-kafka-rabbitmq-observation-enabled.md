# ADR-0057: Enable Kafka/RabbitMQ observation so listener/producer spans actually exist

**Status**: Accepted
**Date**: 2026-08-17 15:20 IST
**Deciders**: Solution/Security Architect

## Context

Live investigation of order 77's trace (Tempo) showed only order-service's own HTTP-handling spans — no Kafka producer span for `OutboxPoller`'s publish, no Kafka consumer span for `PaymentSagaConsumer`'s processing, despite Loki and the database both confirming payment-service genuinely processed the order correctly (matching `correlationId`/`appTraceId` everywhere). This was initially suspected to be a gap in ADR-0056's attribute-stamping — it wasn't. Confirmed directly: payment-service **does** export spans to Tempo (found a real `security filterchain before` trace from an inbound HTTP request), just never for its Kafka-listener-triggered work.

Root cause, confirmed by checking config directly: `spring.kafka.listener.observation-enabled` and `spring.kafka.template.observation-enabled` were never set anywhere in this codebase. Spring Kafka's Micrometer Observation instrumentation for `@KafkaListener` invocations and `KafkaTemplate` sends is **opt-in**, off by default. Without it, no Observation — and therefore no OTel span — is ever created for that work at all. `OrderCorrelationScope`'s `Span.current().setAttribute(...)` (ADR-0056) was always correct; it simply had no real span to attach to on a Kafka listener thread, only OTel's no-op span, which is never exported anywhere.

Checked whether the same applies to RabbitMQ (`notification-service`'s `NotificationDispatchWorker`): yes, and for a related but distinct reason — `RabbitMqConfig` manually constructs its own `RabbitTemplate` and `SimpleRabbitListenerContainerFactory` beans (needed for the DLQ/retry wiring, ADR-0003), bypassing Spring Boot's auto-configuration entirely. The standard `spring.rabbitmq.template.observation-enabled` / `spring.rabbitmq.listener.simple.observation-enabled` properties only apply to Boot's *auto-configured* beans — they have no effect on these custom ones. This needed an explicit code change, not just a config flag.

## Decision

1. **`k8s/base/configmap-common.yaml`**: added `SPRING_KAFKA_LISTENER_OBSERVATION_ENABLED: "true"` and `SPRING_KAFKA_TEMPLATE_OBSERVATION_ENABLED: "true"`. No manually-constructed `KafkaTemplate`/listener-container-factory beans exist anywhere in this codebase (confirmed via repo-wide search) — every service uses Spring Boot's auto-configured ones, so this config-only change is sufficient for order-service, inventory-service, payment-service, and notification-service's Kafka consumers.

2. **`notification-service/RabbitMqConfig.java`**: explicitly called `template.setObservationEnabled(true)` on the manually-built `RabbitTemplate` bean, and `factory.setObservationEnabled(true)` on the manually-built `SimpleRabbitListenerContainerFactory` bean — the code-level equivalent of the config properties, required specifically because these beans bypass Boot's auto-configuration.

## Regression guard

- `./mvnw -pl notification-service -am test`: `BUILD SUCCESS` — confirms `setObservationEnabled` exists on both types in the Spring AMQP version this project uses (compile-checked, not assumed) and existing tests still pass.
- Full multi-module suite (`./mvnw test`, all 11 modules): **BUILD SUCCESS**, 07:54 min, no regressions.
- **Live verification (the part that actually matters)**: after redeploy, place a fresh order, then query Tempo directly for `payment-service`/`inventory-service` spans with `orderId=<N>` and for `notification-service`'s `NotificationDispatchWorker` span — confirming real spans now exist where none did before, not just that the config was applied.

## Consequences

- Positive: once live-verified, this is the piece that actually makes ADR-0056's attribute-stamping visible for the 3 services that were previously invisible in Tempo — the attributes were always correct, they just had nowhere real to land.
- Positive: also closes the producer-side gap (`OutboxPoller`'s Kafka sends), which hadn't been separately identified until this investigation — the consumer-side gap and producer-side gap share one root cause and one fix.
- Negative / accepted: minor additional per-message overhead from Observation instrumentation on every Kafka/RabbitMQ message, accepted the same way `MANAGEMENT_TRACING_ENABLED` was already accepted — opt-in tracing has a cost, and this project has consistently chosen visibility over that marginal cost elsewhere.
- Still open, separately tracked: the gateway's own span for authenticated/proxied routes (e.g. `POST /order`) does not appear in Tempo at all, even for non-force-traced, regular sampled traffic — confirmed distinct from this issue (the gateway does export spans for `permitAll()` routes like `/actuator/prometheus`), not caused by and not fixed by this ADR. Needs its own dedicated investigation.

## Related

- ADR-0056: the attribute-stamping fix this ADR gives something real to attach to.
- ADR-0003: the transactional-outbox/DLQ RabbitMQ wiring that is why `RabbitMqConfig` has manually-constructed beans in the first place.
- ADR-0032: `MANAGEMENT_TRACING_ENABLED`'s original opt-in-tracing decision, the same posture this ADR's overhead trade-off follows.
