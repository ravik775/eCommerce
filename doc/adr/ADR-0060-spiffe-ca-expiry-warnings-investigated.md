# ADR-0060: SPIFFE trust-bundle "expired CA" warnings investigated — benign

**Status**: Accepted (no code change)
**Date**: 2026-08-17 17:10 IST
**Deciders**: Solution/Security Architect

## Context

While debugging an unrelated saga issue (order 79), inventory-service and payment-service logs showed repeated WARN-level entries at every SVID-reconcile cycle:
```
The trusted certificate with alias [spire-ca-1] ... is not valid due to [NotAfter: Mon Aug 17 01:56:36 GMT 2026].
Certificates signed by this trusted certificate WILL be accepted
```
Three separate CA aliases (`spire-ca-1`, `spire-ca-3`, `spire-ca-4`), all past their `NotAfter` timestamp — flagged as a genuine, high-priority security finding requiring investigation before this session ended.

## Investigation

1. **SPIRE server's own logs** show its CA-rotation mechanism operating correctly and on schedule: `"X509 CA activated" expiration="2026-08-17 14:11:24"` at 02:46 UTC, followed by a fresh rotation `"X509 CA activated" expiration="2026-08-18 02:24:39"` at 10:11 UTC — new CAs are being minted and activated automatically, exactly as designed (this is the mechanism ADR-0051 hardened earlier in this engagement).
2. **Tomcat's own log line is explicit that these entries are non-blocking**: *"Certificates signed by this trusted certificate WILL be accepted"* — this is SPIRE/Tomcat's normal trust-bundle overlap behavior during rotation: superseded CAs are kept in the trust store for some period after a new one is activated (avoiding a "flag day" where certificates issued under the old CA suddenly become untrusted), and Tomcat logs their technical expiry as an informational WARN, not an error.
3. **Live functional confirmation**: this session placed and completed multiple real orders (74, 76, 77, 78, 79, 80) throughout the exact window these warnings were firing continuously — `payment-service`'s outbound mTLS call to `order-service` (`OrderServiceClient.getOrderAmount()`, the one synchronous cross-service call in the whole saga, ADR-0007) succeeded every time. No `SSLHandshakeException`, no certificate-related failure, anywhere in either service's logs.

## Decision

**No code or configuration change** — this is confirmed benign: normal SPIRE trust-bundle rotation overlap, not an active vulnerability or functional break. The mesh's actual security property (only currently-valid SVIDs are issued and used for connections) is intact; what's "expired" is old trust-anchor bookkeeping that hasn't been pruned from the local trust store yet, which is expected behavior, not a gap.

## Consequences

- Positive: confirms the SVID rotation hardening from ADR-0051 is genuinely working end-to-end in production-like conditions, not just in isolation.
- Neutral: the WARN-level log noise itself is arguably worth quieting (repeats on every SVID-reconcile cycle, per-service), but that's a log-hygiene nice-to-have, not a security fix — not undertaken here since it has no functional impact and this ADR's purpose was to resolve the security question, not general log cleanliness.
- Follow-up (low priority, not scheduled): if this noise becomes a genuine operational annoyance, investigate whether SPIRE's `ca_ttl`/bundle-pruning configuration can be tuned to drop fully-superseded CAs from the trust bundle sooner, or whether Tomcat's SVID-reload logic can filter already-logged-as-informational entries from repeating every reconcile cycle.

## Related

- ADR-0051: the SVID rotation hardening this ADR confirms is working correctly under live conditions.
- ADR-0002: the original SPIFFE mTLS decision this trust-bundle mechanism serves.
