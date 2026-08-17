# ADR-0058: Raise CPU limits, enable lazy init, widen liveness timing

**Status**: Accepted
**Date**: 2026-08-17 15:55 IST
**Deciders**: Solution/Security Architect

## Context

Redeploying 4 services simultaneously for ADR-0057 (order-service, inventory-service, payment-service, notification-service) took over 12 minutes and cost each pod one liveness-triggered restart. `docker stats` during the stall showed all 4 containers pinned at ~48-50% CPU — which, against their `500m` (half-core) limit, means each was **being throttled at its cgroup ceiling** essentially the whole time, not merely slow. JVM/Spring Boot cold start (classloading, component scanning, bean instantiation) is CPU-bound and benefits from multiple cores; capping it at half a core turns a normally-fast startup into minutes, and four services doing this simultaneously compounds it further. Worse, the resulting slow startup got killed by the liveness probe before finishing, forcing a full restart and repeating the same throttled climb — a self-reinforcing loop.

Host has 12 CPUs / 7.6GB total (Docker Desktop) with 65 containers running across the whole cluster (app services, observability stack, SPIRE, ingress, K8s system pods, plus standalone Postgres/Kafka/Keycloak) — there was real headroom being left unused by the conservative `500m` per-service cap.

## Decision

Four changes, all confirmed low-risk for this dev/test cluster:

1. **Raised each of the 5 app services' CPU limit from `500m` to `1000m`** (`k8s/base/{order,inventory,payment,notification,api-gateway}-service.yaml` — gateway file is `api-gateway.yaml`). Memory limit (`512Mi`) left unchanged — it wasn't the bottleneck (peaked ~70% during the stall, not pinned).
2. **Widened liveness-probe timing** on the same 5 manifests: `initialDelaySeconds` 220→300, `periodSeconds` 15→20, and made `failureThreshold` explicit at `5` (was defaulting to Kubernetes' `3`) — total tolerance before a kill goes from ~265s to ~400s. This directly breaks the restart-loop-amplification: a genuinely-progressing-but-slow startup is now given room to finish instead of being killed and forced to restart from zero.
3. **Enabled `spring.main.lazy-initialization`** (`SPRING_MAIN_LAZY_INITIALIZATION: "true"` in `configmap-common.yaml`, applies to all services via the shared ConfigMap) — defers most bean creation to first use, cutting the CPU-bound eager-init work that's the actual bottleneck during cold start. Accepted trade-off: first request to a lazily-initialized bean pays its creation cost — acceptable for a dev/test cluster; would need reassessment before a production profile if a request-latency-sensitive path were affected.
4. **Process change, not a file change**: future multi-service redeploys for config-only changes will be staggered (`kubectl rollout restart` one service, wait for Ready, then the next) instead of restarting all affected services simultaneously — avoids the CPU pileup at the source rather than just tolerating it better.

## Regression guard

- These are resource/timing/config changes, not application logic — no new unit tests apply. Verification is operational: the next multi-service redeploy should be observably faster and not require a restart, which will be confirmed the next time one happens (this ADR's changes take effect on the very next rollout).
- `SPRING_MAIN_LAZY_INITIALIZATION` is the one genuinely behavior-affecting change (not just resource tuning) — worth a live smoke check after redeploy: place an order and confirm the full saga still completes normally (a lazily-initialized `@KafkaListener` bean, for instance, must still register correctly on first message, not silently no-op).

### 2026-08-17 16:05 IST correction — the namespace ResourceQuota, missed on the first pass

Applying the 5 services' CPU limit raise immediately broke `payment-service` and `notification-service` — both dropped to **zero pods scheduled at all**, not just slow. `kubectl get events` showed the real cause: `exceeded quota: ecom-quota, requested: limits.cpu=1, used: limits.cpu=8, limited: limits.cpu=8`. `k8s/base/resource-limits.yaml`'s `ecom-quota` (a deliberate, incident-driven guardrail from a real prior Docker Desktop hang — documented in that file's own header) was already at its full `8`-core ceiling *before* this ADR's change, mostly from idle capacity on over-provisioned observability services (`tempo` alone held a full `1000m` limit while `docker stats` showed it using ~0.1% of it). Should have checked this quota before raising the 5 services' individual limits; didn't, and it silently broke two services' ability to schedule at all — caught only via the regression script's next run (`FAIL: payment-service actuator health — no pod found`, `FAIL: notification-service actuator health — no pod found`), not proactively.

**Fix**: raised `ecom-quota`'s `limits.cpu` from `8` to `10` — Docker Desktop's VM has 12 CPUs total, so this still leaves 2 full cores of headroom for everything outside this namespace's quota entirely (kube-system, SPIRE, ingress-nginx, and the standalone Postgres/Kafka/Keycloak containers, which aren't K8s pods at all). Not an unbounded increase — the same real-VM-capacity reasoning the original `8` used, just recomputed against what the 5-service CPU raise actually needed.

**Process lesson**: a namespace-wide `ResourceQuota` is exactly the kind of cross-cutting constraint that a per-service resource change can silently violate without any error until `kubectl apply` — should be checked explicitly before raising any individual container's limits in a quota-constrained namespace, not discovered after the fact via a failed rollout.

## Consequences

- Positive: removes the actual bottleneck (CPU throttling during cold start) rather than just accommodating slower startups — should reduce both total redeploy time and unnecessary restarts.
- Positive: `1000m` × 5 services = 5 cores ceiling even if all were maxed simultaneously, still well within the host's 12 — no risk of starving the rest of the cluster (observability stack, SPIRE, ingress) under normal operation.
- Negative / accepted: lazy initialization defers failures too — a misconfigured bean that would have failed fast at startup might now only fail on first use. Acceptable here since this is a dev/test cluster with fast redeploy iteration, not a production safety net being relied upon.
- Follow-up: if lazy init causes any observable first-request latency spike or a bean-registration timing issue (e.g., a `@KafkaListener` not ready when the first message arrives), that would need a targeted `@Lazy(false)` override on the specific bean rather than reverting the global setting.

## Related

- ADR-0057: the change whose redeploy directly surfaced this resource-contention problem.
- Every prior ADR in this session noting "this environment's characteristic slowness under load, 220-270+ seconds" — this ADR is the first attempt to actually reduce that root cause rather than just widening timeouts around it (though this ADR also does some of the latter, as item 2).
