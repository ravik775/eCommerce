package org.bgm.common.tracing;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.bgm.common.audit.AuditLogger;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * ADR-0032: the CAN_TRACE-gated Settings toggle — when the browser sends
 * {@code X-Force-Trace: true}, this stamps the current request's span
 * with the attribute {@link ErrorAlwaysSampledSpanExporter} checks at
 * export time, forcing it through regardless of the success sample rate.
 * <p>
 * Deliberately just reads the header directly rather than propagating
 * via OTel baggage: Spring Cloud Gateway already forwards every incoming
 * header to the backend it proxies to by default, so the header itself
 * reaches every servlet service on the synchronous HTTP call chain
 * (gateway → catalog/inventory/order/user-service) without any extra
 * propagation machinery — simpler and no less correct for that path.
 * Does NOT extend across the Kafka-driven async saga steps (see
 * TracingAutoConfiguration's Javadoc) — a documented scope boundary,
 * not an oversight.
 */
public class ForceTraceFilter extends OncePerRequestFilter {

    static final String FORCE_TRACE_HEADER = "X-Force-Trace";

    // ADR-0048: found live — a request from a session with no CAN_TRACE
    // role, carrying a manually-added X-Force-Trace header (e.g. via
    // browser devtools, bypassing the UI toggle that only ever *sends*
    // the header for a CAN_TRACE session), still got force-exported to
    // Tempo. The header alone was never actually authorized — only
    // hidden client-side. This runs late enough in the filter chain
    // (see getOrder() in ForceTraceFilterAutoConfiguration) that Spring
    // Security's own JWT resource-server authentication has already
    // populated the SecurityContext by the time this check runs, same
    // ROLE_<name> authority format KeycloakRealmRoleConverter produces
    // for every other @PreAuthorize/hasRole check in this codebase — not
    // a special case, the same enforcement mechanism used everywhere
    // else.
    private static final String CAN_TRACE_AUTHORITY = "ROLE_CAN_TRACE";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("true".equalsIgnoreCase(request.getHeader(FORCE_TRACE_HEADER))) {
            if (callerHasCanTraceRole()) {
                Span.current().setAttribute(ErrorAlwaysSampledSpanExporter.FORCE_TRACE_ATTRIBUTE, true);
            } else {
                auditDenied(request);
            }
        }
        chain.doFilter(request, response);
    }

    // ADR-0048 (2026-08-16 update): the denial was previously silent —
    // fails closed correctly, but left no trail to distinguish "nobody
    // ever tried this" from "someone is repeatedly probing for the gap".
    // Routed through the same AuditLogger (see ADR-0016/ADR-0037) every
    // other SOC2-relevant event in this codebase uses, so it's filterable
    // the same way (auditEvent=FORCE_TRACE_DENIED) without a new sink.
    private void auditDenied(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String principal = authentication != null ? String.valueOf(authentication.getName()) : "anonymous";
        AuditLogger.log("FORCE_TRACE_DENIED", AuditLogger.fields()
                .with("principal", principal)
                .with("path", request.getRequestURI())
                .build());
    }

    // Package-private + static (was private instance): reused as-is by
    // SpanAttributeEnrichmentFilter so both filters make the exact same
    // force-trace authorization decision from one place — duplicating
    // this check instead of sharing it would let the two silently drift
    // (e.g. one filter honoring a role change the other doesn't).
    static boolean callerHasCanTraceRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (CAN_TRACE_AUTHORITY.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
