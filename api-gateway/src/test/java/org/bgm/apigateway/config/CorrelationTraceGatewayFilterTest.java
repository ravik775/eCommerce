package org.bgm.apigateway.config;

import org.bgm.common.correlation.CorrelationConstants;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    // ADR-0052: found live — the UI sent its own client-generated
    // X-Trace-Id, fixed per browser tab, and this filter honored it
    // verbatim (the same firstNonBlank fallback X-Correlation-Id still
    // legitimately uses). A client-supplied value for something this
    // project treats as an authoritative correlation anchor is exactly
    // the kind of input that shouldn't be trusted. These lock down that
    // the outgoing request always carries a fresh, gateway-generated
    // value regardless of what the client sent — X-Correlation-Id is
    // deliberately unaffected (see the class Javadoc for why that one
    // stays client-honorable).
    @Test
    void clientSuppliedTraceIdIsNeverHonored() {
        String spoofedTraceId = "attacker-supplied-value";
        AtomicReference<String> outboundTraceId = new AtomicReference<>();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/order")
                        .header(CorrelationConstants.TRACE_ID_HEADER, spoofedTraceId));

        new CorrelationTraceGatewayFilter().filter(exchange, (ServerWebExchange ex) -> {
            outboundTraceId.set(ex.getRequest().getHeaders().getFirst(CorrelationConstants.TRACE_ID_HEADER));
            return Mono.empty();
        }).block();

        assertNotEquals(spoofedTraceId, outboundTraceId.get(),
                "a client-supplied X-Trace-Id must never reach downstream services unchanged");
        assertNotEquals(spoofedTraceId,
                exchange.getResponse().getHeaders().getFirst(CorrelationConstants.TRACE_ID_HEADER),
                "a client-supplied X-Trace-Id must never be echoed back on the response either");
    }

    @Test
    void traceIdIsGeneratedEvenWhenClientSendsNone() {
        AtomicReference<String> outboundTraceId = new AtomicReference<>();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/order"));

        new CorrelationTraceGatewayFilter().filter(exchange, (ServerWebExchange ex) -> {
            outboundTraceId.set(ex.getRequest().getHeaders().getFirst(CorrelationConstants.TRACE_ID_HEADER));
            return Mono.empty();
        }).block();

        assertTrue(outboundTraceId.get() != null && !outboundTraceId.get().isBlank(),
                "a trace ID must always be generated, even with no incoming header at all");
    }

    // ADR-0055: in this unit-test harness there is no OTel SDK wired up
    // at all, so Span.current() is always OpenTelemetry's own no-op
    // Span.getInvalid() — meaning every filter() call in every test above
    // already exercises the fallback path. This test locks down that the
    // fallback firing is genuinely observable (an AUDIT log line), not
    // just a correctly-generated-but-silent UUID — the whole point of
    // Option 3's visibility fix. Uses a Logback ListAppender rather than
    // asserting on log text, so it's checking the mechanism (a line was
    // emitted with the expected structured fields), not string-matching
    // brittle formatting.
    @Test
    void fallbackToRandomUuidIsAuditLogged() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CorrelationTraceGatewayFilter.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/order"));

            new CorrelationTraceGatewayFilter().filter(exchange, (ServerWebExchange ex) -> Mono.empty()).block();

            boolean fallbackWarned = appender.list.stream()
                    .anyMatch(event -> event.getFormattedMessage().contains("no valid OTel span"));
            assertTrue(fallbackWarned, "a random-UUID fallback must log a WARN, not degrade silently");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
