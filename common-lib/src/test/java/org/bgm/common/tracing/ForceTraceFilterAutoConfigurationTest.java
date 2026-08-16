package org.bgm.common.tracing;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-08-16: locks down the servlet-side counterpart of the fix covered
 * by {@code CorrelationTraceGatewayFilterTest} (api-gateway) — same bug
 * shape, same fix shape, see ADR-0043. Originally registered at
 * {@code Integer.MIN_VALUE + 1} (very early), which meant
 * {@code Span.current()} saw a no-op span — {@code setAttribute()}
 * silently did nothing, so the {@code force_trace} attribute never
 * reached the actually-exported span. Verified live this session (a
 * real Tempo trace showing {@code force_trace: true}) after moving this
 * to {@code Integer.MAX_VALUE - 1}.
 */
class ForceTraceFilterAutoConfigurationTest {

    @Test
    void registersLateInTheServletFilterChainNotEarly() {
        FilterRegistrationBean<ForceTraceFilter> registration =
                new ForceTraceFilterAutoConfiguration().forceTraceFilter();

        assertEquals(Integer.MAX_VALUE - 1, registration.getOrder(),
                "must run late so Spring's own HTTP tracing instrumentation has already created the "
                        + "real span — reverting to an early order silently breaks the CAN_TRACE force-trace "
                        + "toggle with no compile-time signal; see ADR-0043");
        assertTrue(registration.getOrder() > 0,
                "must not run near Integer.MIN_VALUE — that was the original bug");
    }
}
