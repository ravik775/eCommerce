# ADR-0009: Gateway rate limiter backend — Redis-backed distributed limiter

**Status**: Accepted
**Date**: 2026-08-12
**Deciders**: Solution/Security Architect (raised in architect self-review, `doc/architecture/11-architect-review.md`, Conflict #2)

## Context

The gateway's draft config wires a Redis-backed token-bucket rate limiter (inherited from the original config-server drafts) without ADR-level justification. Resilience4j — already a dependency and this project's chosen resilience library (`05-technology-architecture.md`) — has its own in-process `RateLimiter` module, raising the question of whether Redis is earning its place as infrastructure component.

## Options Considered

| Option | Pros | Cons |
|---|---|---|
| Resilience4j in-memory RateLimiter | No extra infrastructure component; simplest possible implementation | Only correct if the API Gateway runs as a single replica — each replica would otherwise enforce its own separate limit, silently multiplying the effective cap by replica count |
| Redis-backed distributed limiter | Correct rate enforcement across any number of gateway replicas — required once the gateway is horizontally scaled | Adds Redis as a stack component; a small amount of added latency per request for the Redis round-trip |

## Evidence

- "For a single instance, Resilience4j RateLimiter is typically sufficient... if your service is elastic with a varying number of instances... you would need a rate limiter that maintains its data in a distributed cache" such as Redis. ([reflectoring.io — Rate Limiting with Resilience4j](https://reflectoring.io/rate-limiting-with-resilience4j/))
- Redis-based limiters achieve correctness across replicas because "all Redis operations for a single rate-limit check/update are performed as an atomic transaction, allowing rate limiters running on separate processes or machines to share state safely" — at the cost of the added network hop per check. ([INNOQ — Distributed Rate Limiting with Spring Boot and Redis](https://www.innoq.com/en/blog/2024/03/distributed-rate-limiting-with-spring-boot-and-redis/))

## Decision

The API Gateway will run with **multiple replicas for availability** (confirmed by decision-maker), which makes a distributed rate limiter a correctness requirement, not an optional enhancement. The gateway uses **Redis-backed rate limiting** (the already-drafted `RequestRateLimiter` filter + `userKeyResolver` bean), consolidated into the single canonical gateway config per ADR-0005.

## Consequences

- Positive: rate limiting is correct regardless of gateway replica count, which matters for an internet-facing edge component expected to scale for availability.
- Negative / accepted trade-off: Redis is a required stack component (already implicitly planned for Docker Compose/Kubernetes in the delivery plan) rather than a deferred one; each rate-limit check costs a small added network round-trip versus an in-memory check.
- Follow-up required: Redis must be included with appropriate persistence/availability settings (it is on the request hot-path for every gateway-routed call) when Redis is provisioned in Phase 5 (Docker Compose) and Phase 6 (Kubernetes).

## Related

- Enriches: ADR-0005 (API Gateway responsibilities) — this ADR formalizes the reasoning behind the Redis choice ADR-0005 assumed without justifying.
- Resolves Conflict #2 from `doc/architecture/11-architect-review.md`
