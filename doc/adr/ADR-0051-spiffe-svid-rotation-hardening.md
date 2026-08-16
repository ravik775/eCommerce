# ADR-0051: SPIFFE SVID rotation hardening — tighter reload interval, expiry-margin alerting, and gateway retry/backoff

**Status**: Accepted
**Date**: 2026-08-16 22:04 IST
**Deciders**: Solution/Security Architect

## Context

A live incident (analyzed the same session): a `GET /user/me` request failed with a generic `500`, root-caused via gateway logs to `javax.net.ssl.SSLHandshakeException: (certificate_expired) Cert chain cannot be verified` on the gateway's outbound call to `user-service`. This traced back to a ~3-second SPIRE Workload API gRPC stream disconnect on `user-service` (`UNAVAILABLE: Network closed for unknown reason` / `io exception`, immediately followed by a successful reconnect: `Received X509Context update`).

Researched, not guessed, before proposing a fix:

- **Two independent SVID-refresh paths exist, with very different reliability.** The gateway's outbound Netty client (`SpiffeGatewayMtlsAutoConfiguration`) wires `SpiffeKeyManager`/`SpiffeTrustManager` directly against the live `X509Source` — every handshake reads the current in-memory snapshot fresh, no separate caching layer. Servlet backends' inbound Tomcat connector (`SpiffeInboundMtlsAutoConfiguration`) cannot use that same live path — a documented Spring Boot/Tomcat bug silently ignores a custom `SSLContext` wired directly into `SSLHostConfigCertificate` (spring-projects/spring-boot#47326, already noted in that class's own Javadoc) — so it falls back to writing PEM files to disk and calling Tomcat's `reloadSslHostConfigs()` on a **fixed 5-minute poll**, completely decoupled from the SVID's actual freshness.
- **The library gives no better option than polling.** Decompiled `java-spiffe-core-0.8.17.jar` directly (`javap` against `X509Source`/`DefaultX509Source`) rather than assuming from documentation: `DefaultX509Source`'s own Workload API watcher callback (`setX509ContextWatcher`) is `private`, used only internally to update a `volatile` in-memory snapshot — there is no public listener/callback API to subscribe to. `getX509Svid()` always returns "whatever was last successfully pushed," with no proactive invalidation on stream disconnect — it keeps serving a snapshot that's aging toward its actual expiry for as long as the stream stays down, with nothing marking it as suspect.
- **Sequence that produced the incident**: `user-service`'s Workload API stream disconnected → its `X509Source` kept returning the last cached (now-aging) SVID during the gap → the 5-minute Tomcat reload cycle wasn't due yet → the served certificate's actual TTL ran out before the next scheduled reload → the gateway's live client-side check correctly rejected it.

This is the same class of previously-flagged-but-never-fully-fixed SPIFFE SVID rotation fragility noted repeatedly earlier in this project (worked around each time via a pod restart, never root-caused until this investigation).

## Decision

Three complementary changes, layered rather than relying on any single one:

1. **Tighten the Tomcat reload interval from 5 minutes to 30 seconds** (`SpiffeInboundMtlsAutoConfiguration.SvidRotationLifecycle`, `RELOAD_INTERVAL_SECONDS`). Directly shrinks the exposure window for the common case (a rotation happened, the file just hadn't caught up yet) by roughly 10x. Cheap: the reload itself is a local file write plus an in-process Tomcat SSL-context swap, not a network call.

2. **Proactive expiry-margin warning** (`checkExpiryMargin()`, same class): on every poll tick, check the currently-active SVID's remaining validity; if it's under 2 minutes, log a `WARN` — a healthy Workload API connection rotates well before that point, so this margin being breached means the stream is already unhealthy, not that expiry is merely approaching on schedule. This doesn't fix the "Workload API is actually down" case (no amount of local polling can conjure a certificate the SPIRE agent hasn't delivered yet), but it makes that condition observable *before* a real request fails on it, instead of only after — consistent with this project's "surface a gap before it's found by someone else" pattern (ADR-0048).

3. **Gateway-side retry/backoff for the residual race window** — added a `Retry` filter (1 retry, `SERVER_ERROR` series, `BAD_GATEWAY`/`SERVICE_UNAVAILABLE`/`GATEWAY_TIMEOUT` statuses) to the `order-service`, `user-service`, and `inventory-service` routes in `k8s/base/configmap-gateway-routes.yaml`, matching the pattern already established on `catalog-service-read`/`catalog-service-admin`. Restricted to `methods: [GET]` on every route, same non-idempotent-write safety rule already used for catalog: `order-service-create` (POST, order creation) and any write path on `inventory-service` must never be silently replayed by a retry. `RetryGatewayFilterFactory`'s default `exceptions` list already includes `java.io.IOException`, and `javax.net.ssl.SSLHandshakeException` is a subtype of it, so no explicit `exceptions:` override was needed to cover the exact failure this ADR investigates.

### Why not more

- **Event-driven reload instead of polling**: not possible without forking or wrapping the library — no public hook exists (see Context). Filed as a real upstream-library limitation, not something to work around with reflection into a private field.
- **Retry alone, no interval/margin changes**: rejected — a retry only helps if it happens to land after the backend's next successful reload, which isn't guaranteed under sustained SPIRE instability; it papers over the specific incident without shrinking the actual exposure window.
- **Explicit SVID TTL configuration** (`spire-register.sh` currently sets no TTL, inheriting SPIRE server's default): left as a follow-up, not bundled into this change — widening the TTL trades off against the standard SPIFFE security posture of short-lived credentials, and the actual server-side default wasn't part of this session's live-verified investigation (unlike everything above, which was traced through this codebase's real logs and the real library bytecode).

## Regression guard

`SpiffeInboundMtlsAutoConfiguration` has no existing unit tests (requires a real Tomcat `Connector`/`X509Source`/Workload API socket to exercise meaningfully) — consistent with this project's established pattern for this class of infra-heavy SPIFFE code (see ADR-0002), this change is verified functionally rather than with a new artificial unit test: confirm `SvidRotationLifecycle`'s WARN line appears in `kubectl logs` when a backend's SVID margin is deliberately forced low (e.g., temporarily shortening `EXPIRY_WARNING_MARGIN` or simulating a Workload API outage), and confirm the gateway's new `Retry` filters actually fire during a live-simulated rotation gap by checking for a retried request in the gateway's access pattern (a `Retry`'d request shows as a single client-visible success despite an internal `BAD_GATEWAY`/`SERVICE_UNAVAILABLE` on the first attempt).

`common-lib`, `api-gateway`, and `user-service`'s full test suites (43 tests total) all still pass unchanged after this change, and `catalog-service`/`inventory-service`/`notification-service`/`order-service`/`payment-service` all still compile against the updated `common-lib` — confirming this hardening didn't regress any of the CAN_TRACE (ADR-0048), session-max-age (ADR-0049), or role-sync (ADR-0050) fixes from earlier in this same session.

## Consequences

- Positive: closes a real, live-reproduced incident with a root-caused, layered fix rather than a single point patch; the expiry-margin warning gives operators visibility into SPIRE agent instability before it causes a user-visible failure, not just after.
- Negative / accepted trade-off: the 30-second poll does marginally more file I/O and Tomcat SSL-context reload work than the 5-minute one did — negligible at this system's scale (a handful of backend pods, per ADR-0036's capacity planning). The proactive-warning threshold and retry counts are static rather than adaptive; if the SPIRE agent's actual instability characteristics change significantly, these constants may need revisiting.
- Follow-up required: consider an explicit, longer SVID TTL for local/dev SPIRE registration entries if this class of incident recurs despite this hardening — not pursued now since it wasn't part of this session's verified investigation.

## Related

- Related: ADR-0002 (original SPIFFE mTLS decision — this ADR hardens its rotation robustness, doesn't change its design), ADR-0009 (Redis-backed rate limiter — the same gateway-routes ConfigMap this ADR also touches), ADR-0048 (the "surface a gap before it's found by someone else" pattern this ADR's expiry-margin warning follows)
