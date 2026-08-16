# ADR-0041: Load testing — deferred now, base strategy defined for when it's needed

**Status**: Accepted
**Date**: 2026-08-16
**Deciders**: Solution/Security Architect

## Context

ADR-0036's capacity estimate (compute headroom is 1–2 orders of magnitude above the ~4 req/s sustained load this system will see) was explicitly flagged as **unverified by an actual test** — no load test has ever been run against this system, and none is wired into CI. This ADR makes that a named decision (not performed, and why) rather than a silent absence, and defines the strategy to use *when* a load test becomes worth running, so that trigger isn't a cold start.

## Decision: not performed now

**No load test is being run as part of this review.** Justification, not just deferral for its own sake:
- The system's user base is fixed and small by design — 100 internal users, ~20 concurrently active (ADR-0036) — not an organically growing consumer product where "what if we go viral" is a real risk.
- ADR-0036's estimate already shows an order-of-magnitude-plus safety margin between estimated peak load (~20–40 req/s) and provisioned per-service capacity (500m CPU limit, generously hundreds of req/s for this workload shape). A load test's main value — catching a capacity cliff before it's a real incident — has low expected payoff when the estimated margin is already this wide.
- Every service in this deployment runs a **single replica** (ADR-0034) — the actual failure mode this system is exposed to is an availability/HA gap, not a throughput ceiling. A load test would validate a dimension (raw throughput) that isn't this system's binding constraint; time is better spent on the availability gaps already tracked (ADR-0034's untested-restore follow-up, single-replica posture).

This is a scope decision, not a capability gap — the base strategy below exists precisely so this can be picked up quickly once the trigger condition below is met.

## Base strategy for when it's needed

**Trigger conditions** (any one is sufficient to revisit this decision):
1. User count assumption changes — this tool opens to more than the ~100-user/20-active ceiling this whole capacity model rests on (ADR-0036's own stated invalidation condition).
2. A real SLA or uptime commitment is made to any stakeholder — at that point "we estimated it's fine" stops being an acceptable answer.
3. Before any production go-live that isn't purely internal, regardless of user-count assumptions holding — a load test is cheap insurance against an estimate being wrong in a way that matters once real (not internal-only) users are affected.

**Tool: k6**, over Gatling. Evidence for this system specifically, not a generic "k6 is better" claim:
- This project's existing observability stack is Prometheus + Grafana (ADR/Phase 7 work) — k6 has native Grafana/Prometheus output integration, meaning load-test results plug directly into dashboards that already exist, rather than requiring a separate Gatling HTML-report workflow.
- k6 test scripts are JavaScript — this project's UI (`ui/src/app.js`) is already plain JavaScript, and the same `apiFetch`-style request shapes used there translate directly into k6 scenarios with no new language for whoever picks this up.
- k6 is a single Go binary with lighter resource consumption than Gatling's JVM/Akka-based engine — a meaningful fit for a project that has consistently avoided adding heavyweight infrastructure (ADR-0002, ADR-0013, ADR-0014, ADR-0015, ADR-0021, ADR-0022) and runs everything locally on a resource-constrained Docker Desktop VM (ADR-0036's own resource-quota findings).
- Gatling remains the better choice *if* this ever becomes a JVM-shop-with-massive-concurrency scenario (per the research: Gatling packs enormous virtual-user counts onto a single machine via its actor model) — not this system's profile at 20 active users.

**Target scenarios**, when written: (1) checkout flow end-to-end (cart → order → payment saga) at ~2x the ADR-0036 peak estimate (40–80 req/s) sustained for several minutes, watching for saga-consistency degradation, not just latency; (2) catalog browse/search under the same load, since that's the highest-read-volume path against the 10,000+ product table; (3) a soak test (lower load, multi-hour) specifically to catch the kind of slow leak a short burst test wouldn't — relevant given this system has never been run continuously under any load before.

## Consequences

- Positive: "why isn't there a load test" now has a written, defensible answer instead of silence; if the trigger conditions are ever met, the tool choice and target scenarios are already decided, so execution can start immediately instead of re-researching from zero.
- Negative / accepted trade-off: ADR-0036's capacity numbers remain estimates until this is actually run — genuinely accepted risk, bounded by the wide safety margin already calculated there.
- Follow-up required: none until a trigger condition fires. If one does, the next action is writing the three k6 scenarios above against a real (not local Docker Desktop) environment sized closer to the intended deployment target.

## Related

- Related: ADR-0036 (the capacity estimate this ADR's decision responds to), ADR-0034 (why availability, not throughput, is this system's actual binding constraint)

Sources:
- [Load Testing Showdown: K6 vs Gatling for Modern Applications](https://medium.com/@abhic43/load-testing-showdown-k6-vs-gatling-for-modern-applications-d4755ed9d553)
- [Gatling vs k6 2026: Performance & Load Testing Compared](https://qaskills.sh/blog/gatling-vs-k6-performance-testing-2026)
- [Gatling vs K6: choosing the best load testing solution](https://gatling.io/blog/gatling-vs-k6)
