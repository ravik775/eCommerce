package org.bgm.common.tracing;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-08-16: locks down a real bug found live — a request from a
 * session with no CAN_TRACE role, carrying a manually-added
 * X-Force-Trace header (bypassing the UI, which only ever *sends* the
 * header for a CAN_TRACE session — client-side gating, never
 * enforcement), still got force-exported to Tempo. Confirmed via a real
 * TraceQL search finding a {@code force_trace: true} span from a
 * customer1 (CUSTOMER-only) request. See ADR-0048.
 */
class ForceTraceFilterTest {

    private SdkTracerProvider tracerProvider;
    private InMemorySpanExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        tracerProvider.close();
    }

    @Test
    void doesNotStampAttributeWhenCallerLacksCanTraceRole() throws Exception {
        setAuthenticationWithRoles("ROLE_CUSTOMER");

        assertFalse(runFilterAndCheckForceTraceAttribute(true),
                "a CUSTOMER-only caller sending the header must not get force-traced");
    }

    @Test
    void stampsAttributeWhenCallerHasCanTraceRole() throws Exception {
        setAuthenticationWithRoles("ROLE_CAN_TRACE");

        assertTrue(runFilterAndCheckForceTraceAttribute(true),
                "a CAN_TRACE caller sending the header must get force-traced");
    }

    @Test
    void doesNotStampAttributeWhenHeaderAbsentEvenWithRole() throws Exception {
        setAuthenticationWithRoles("ROLE_CAN_TRACE");

        assertFalse(runFilterAndCheckForceTraceAttribute(false),
                "a CAN_TRACE caller who didn't ask for it must not get force-traced");
    }

    @Test
    void doesNotStampAttributeWhenNoAuthenticationPresent() throws Exception {
        assertFalse(runFilterAndCheckForceTraceAttribute(true),
                "an unauthenticated context must fail closed, not force-trace");
    }

    @Test
    void auditsDenialWhenCallerLacksCanTraceRole() throws Exception {
        setAuthenticationWithRoles("ROLE_CUSTOMER");
        ListAppender<ILoggingEvent> appender = attachAuditAppender();

        runFilterAndCheckForceTraceAttribute(true);

        assertTrue(appender.list.stream().anyMatch(e ->
                        e.getFormattedMessage().contains("auditEvent=FORCE_TRACE_DENIED")
                                && e.getFormattedMessage().contains("principal=test-user")),
                "a denied force-trace attempt must be audit-logged for later filtering");
        detachAuditAppender(appender);
    }

    @Test
    void doesNotAuditWhenCallerHasCanTraceRole() throws Exception {
        setAuthenticationWithRoles("ROLE_CAN_TRACE");
        ListAppender<ILoggingEvent> appender = attachAuditAppender();

        runFilterAndCheckForceTraceAttribute(true);

        assertTrue(appender.list.stream().noneMatch(e -> e.getFormattedMessage().contains("FORCE_TRACE_DENIED")),
                "an authorized caller's request must not be logged as a denial");
        detachAuditAppender(appender);
    }

    private ListAppender<ILoggingEvent> attachAuditAppender() {
        Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
        return appender;
    }

    private void detachAuditAppender(ListAppender<ILoggingEvent> appender) {
        Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        auditLogger.detachAppender(appender);
        appender.stop();
    }

    private void setAuthenticationWithRoles(String... authorities) {
        var grantedAuthorities = List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("test-user", "n/a", grantedAuthorities));
    }

    private boolean runFilterAndCheckForceTraceAttribute(boolean sendHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (sendHeader) {
            request.addHeader("X-Force-Trace", "true");
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { };

        Span span = tracerProvider.get("test").spanBuilder("test-span").startSpan();
        try (Scope scope = span.makeCurrent()) {
            new ForceTraceFilter().doFilter(request, response, chain);
        } finally {
            span.end();
        }

        return exporter.getFinishedSpanItems().stream()
                .anyMatch(s -> Boolean.TRUE.equals(s.getAttributes().get(ErrorAlwaysSampledSpanExporter.FORCE_TRACE_ATTRIBUTE)));
    }
}
