package org.bgm.apigateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-08-16: locks down the filter-ordering fix that made cross-service
 * distributed tracing actually work (a single connected trace tree
 * instead of an independently-rooted trace per hop — verified live via
 * a real checkout producing a backend span with a genuine external
 * parent span ID; see ADR-0043).
 * <p>
 * This filter previously ran at {@code Ordered.HIGHEST_PRECEDENCE},
 * before WebFlux's own HTTP-server observation instrumentation had
 * created the request's span — {@code Context.current()} was a no-op at
 * that point, so neither the {@code force_trace} attribute nor the
 * injected {@code traceparent} header ever reflected a real span. This
 * test doesn't re-run the full reactive pipeline (that needs a live
 * integration test — see ADR-0043's "regression trigger" section for
 * how to re-verify end-to-end) — it protects the one thing a future
 * refactor could silently revert without any other test catching it:
 * the order value itself.
 */
class CorrelationTraceGatewayFilterTest {

    @Test
    void runsLateInTheFilterChainNotHighestPrecedence() {
        int order = new CorrelationTraceGatewayFilter().getOrder();

        assertEquals(Ordered.LOWEST_PRECEDENCE - 1, order,
                "must run just before NettyRoutingFilter (LOWEST_PRECEDENCE) so WebFlux's own span "
                        + "already exists — reverting to HIGHEST_PRECEDENCE silently breaks cross-service "
                        + "trace propagation with no compile-time signal; see ADR-0043");
        assertTrue(order > Ordered.HIGHEST_PRECEDENCE,
                "must not run at HIGHEST_PRECEDENCE — that was the original bug");
    }

    // ADR-0048: found live — a request with a manually-added
    // X-Force-Trace header still force-exported the gateway's own span
    // regardless of the caller's actual roles (the servlet-side
    // equivalent of this bug, ForceTraceFilter, was found the same way —
    // see ForceTraceFilterTest in common-lib). These test the decision
    // logic directly, isolated from the surrounding reactive/OTel-context
    // wiring — see CorrelationTraceGatewayFilter's field Javadoc for why
    // that wiring is verified by live testing instead.

    @Test
    void callerWithCanTraceRoleIsAuthorized() {
        var authentication = new TestingAuthenticationToken(
                "test-user", "n/a", List.of(new SimpleGrantedAuthority("ROLE_CAN_TRACE")));

        assertTrue(new CorrelationTraceGatewayFilter().callerHasCanTraceRole(authentication));
    }

    @Test
    void callerWithoutCanTraceRoleIsNotAuthorized() {
        var authentication = new TestingAuthenticationToken(
                "test-user", "n/a", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));

        assertFalse(new CorrelationTraceGatewayFilter().callerHasCanTraceRole(authentication));
    }

    @Test
    void nullAuthenticationIsNotAuthorized() {
        assertFalse(new CorrelationTraceGatewayFilter().callerHasCanTraceRole(null));
    }
}
