# ADR-0048: Server-side authorization for the CAN_TRACE force-trace header — the client-side gate was never enforcement

**Status**: Accepted
**Date**: 2026-08-16 15:52 IST
**Deciders**: Solution/Security Architect

## Context

Reported and confirmed live: a session with no `CAN_TRACE` role, sending a manually-added `X-Force-Trace: true` header (e.g. via browser devtools — the UI toggle itself only ever *sends* the header when a `CAN_TRACE` session enables it, but that's a client-side convenience, not a barrier to anyone crafting the header directly), still got force-exported to Tempo.

Proven with a real request: logged in as `customer1` (`CUSTOMER` only), confirmed via `GET /user/me` and the Settings menu being correctly hidden in the UI, then sent `fetch(..., {headers: {'X-Force-Trace': 'true'}})` directly. Tempo's `{span.force_trace=true}` search returned a real matching span from that exact request. Both `ForceTraceFilter` (common-lib, every servlet backend) and `CorrelationTraceGatewayFilter` (the gateway) honored the raw header value with zero role check — the same gap in both places, since both were written together under ADR-0032 with the header treated as sufficient on its own.

This is the same category of gap this project has closed before in the opposite direction — ADR-0025's whole premise is that every service independently validates the JWT and re-enforces RBAC rather than trusting the caller (or the UI) to have already gated something. The `CAN_TRACE` toggle was accidentally the one exception: real client-side UX gating, zero server-side enforcement.

## Decision

Both filters now check the caller's actual `ROLE_CAN_TRACE` authority — the same authority string `KeycloakRealmRoleConverter` (servlet side) and `KeycloakOidcUserService` (gateway side) already produce for every other `@PreAuthorize`/`hasRole(...)` check in this codebase, not a new or special mechanism — before honoring the header:

- **`ForceTraceFilter`** (common-lib, servlet): reads `SecurityContextHolder.getContext().getAuthentication()`, which by this filter's own deliberately-late position in the chain (`Integer.MAX_VALUE - 1`, see ADR-0043) is already populated by Spring Security's JWT resource-server authentication.
- **`CorrelationTraceGatewayFilter`** (gateway, reactive): reads `ReactiveSecurityContextHolder.getContext()` instead — WebFlux keeps the security context in Reactor Context, not a ThreadLocal, so the synchronous holder is always empty there; the check is wired as a `.flatMap`-style step in the existing reactive chain, not a synchronous call.

If the header is present but the role check fails (or there's no authentication at all — shouldn't happen this late in an authenticated-only route, but handled the same way regardless), the attribute is simply never set — the request proceeds completely normally, just without forced export. Fails closed, no error surfaced, matching this project's established pattern for every other client-side-convenience/server-side-enforcement pair (e.g. the role-gated UI tabs elsewhere in `app.js`).

## Regression guard

Both fixes are covered by direct unit tests of the decision logic, not just documented as a live finding:
- `ForceTraceFilterTest` (common-lib, 4 cases) — runs the real filter against an in-memory OTel exporter (`InMemorySpanExporter`) with a real `SecurityContextHolder`-backed authentication, asserting the exported span does/doesn't carry `force_trace: true` for each of: `CAN_TRACE` + header present, no `CAN_TRACE` + header present, `CAN_TRACE` + header absent, and no authentication at all.
- `CorrelationTraceGatewayFilterTest` (api-gateway, 3 new cases) — the gateway's `callerHasCanTraceRole(Authentication)` method was made package-private specifically so this decision logic could be tested directly, isolated from the surrounding reactive/OTel-context wiring (which is genuinely hard to unit test in isolation — verified instead by the live reproduction described above, which should be re-run after any future change to this filter: log in as a non-`CAN_TRACE` user, manually add the header via `fetch`, confirm no matching span in Tempo).

## Consequences

- Positive: closes a real authorization gap — this session found and fixed it before it was exploited, but it's exactly the kind of gap that erodes trust in an audit if found by someone else first. The fix is minimal (a role check, not new infrastructure) and consistent with this project's existing RBAC enforcement pattern everywhere else.
- Negative / accepted trade-off: none identified — this closes a gap without introducing new behavior for the correctly-authorized case (a real `CAN_TRACE` session's toggle continues to work exactly as before, verified by the "stamps attribute when caller has CAN_TRACE role" test case in both filters).
- Follow-up required: none currently open.

## Related

- Related: ADR-0032 (the original `CAN_TRACE` toggle decision — this ADR closes the enforcement gap that decision's implementation shipped with), ADR-0025 (JWT/RBAC — the general "every service independently enforces roles" pattern this ADR brings the force-trace path into line with), ADR-0043 (the filter-ordering fixes this ADR's server-side check builds on — both filters already had to run late enough for `Span.current()`/security-context resolution to work correctly before this role check could even be added)

## 2026-08-16 17:05 IST update — audit logging for denied attempts

The original fix above fails closed silently: a caller without `CAN_TRACE` sending the header simply proceeds untraced, with nothing recorded distinguishing "nobody ever tried this" from "someone is repeatedly probing for the gap." Requested follow-up: log an audit event on every denial so it's filterable later.

Both filters now call the existing `AuditLogger` (see ADR-0016/ADR-0037 — the same "AUDIT" logger name every other SOC2-relevant event in this codebase uses, no new sink introduced) with event name `FORCE_TRACE_DENIED` whenever the header is present but the role check fails:

- **`ForceTraceFilter`** (common-lib): logs `principal` (the authenticated name, or `"anonymous"` if there's no authentication at all) and `path` (`request.getRequestURI()`).
- **`CorrelationTraceGatewayFilter`** (gateway): logs the same two fields, read from the reactive `Authentication`/`ServerWebExchange` instead.

Both use the same event name and field names so a single denied attempt is joinable across the gateway hop and any downstream servlet hop it reaches (same join pattern `AuditLogger`'s own Javadoc already documents for LOGIN→ORDER_CREATED). No new tests were added for the OTel/span behavior (unchanged — a denial still simply never stamps the attribute); the audit line is additive logging only, not new decision logic, so verification is functional (grep `auditEvent=FORCE_TRACE_DENIED` in the affected pod's logs after a live reproduction) rather than a new unit test suite.

### Consequences (update)

- Positive: a denied force-trace attempt is now visible in the same log stream every other audit event uses, without standing up new infrastructure — closes the "erodes trust in an audit if found by someone else first" concern from the original decision by making the *attempt*, not just the code fix, observable.
- Negative / accepted trade-off: one INFO-level audit line per denied attempt — negligible volume expected (this requires deliberately crafting the header without the role, not something normal UI usage triggers), consistent with this logger's existing volume profile.
- Follow-up required: none currently open.
