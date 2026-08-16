package org.bgm.apigateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
