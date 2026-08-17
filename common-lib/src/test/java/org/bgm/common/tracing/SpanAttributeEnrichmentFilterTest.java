package org.bgm.common.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.servlet.FilterChain;
import org.bgm.common.correlation.CorrelationConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Grafana/Tempo showed a real checkout trace with only the default HTTP
 * instrumentation attributes (method/uri/status/outcome/exception) — no
 * correlationId/orderId/appTraceId/force_trace, even though all four were
 * present in MDC/logs at the same moment. Locks down that
 * SpanAttributeEnrichmentFilter actually closes that gap, and that it
 * reads MDC only after the rest of the chain runs (needed for orderId,
 * which order-service's OrderService only puts into MDC partway through
 * handling the order-creation request — see the filter's own Javadoc).
 */
class SpanAttributeEnrichmentFilterTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private Tracer tracer;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetry otel = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
        tracer = otel.getTracer("test");
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void stampsCorrelationIdOrderIdAndAppTraceIdSetDuringTheChain() throws Exception {
        SpanAttributeEnrichmentFilter filter = new SpanAttributeEnrichmentFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            // Simulates order-service's OrderService: orderId only known
            // partway through the controller, not before the chain starts.
            MDC.put(CorrelationConstants.MDC_CORRELATION_ID_KEY, "corr-123");
            MDC.put(CorrelationConstants.MDC_ORDER_ID_KEY, "58");
            MDC.put(CorrelationConstants.MDC_TRACE_ID_KEY, "trace-abc");
        };

        SpanData span = runWithinSpan(() -> filter.doFilter(request, response, chain));

        assertEquals("corr-123", span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("correlationId")));
        assertEquals("58", span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("orderId")));
        assertEquals("trace-abc", span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("appTraceId")));
    }

    @Test
    void omitsAttributesWhenMdcFieldIsAbsent() throws Exception {
        SpanAttributeEnrichmentFilter filter = new SpanAttributeEnrichmentFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/products/search");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            // A catalog browse has no order context at all.
            MDC.put(CorrelationConstants.MDC_CORRELATION_ID_KEY, "corr-456");
        };

        SpanData span = runWithinSpan(() -> filter.doFilter(request, response, chain));

        assertEquals("corr-456", span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("correlationId")));
        assertNull(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("orderId")));
        assertNull(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("appTraceId")));
    }

    @Test
    void tagsForceTraceOnlyWhenHeaderPresentAndCallerAuthorized() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "tracer1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_CAN_TRACE"))));
        SpanAttributeEnrichmentFilter filter = new SpanAttributeEnrichmentFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders");
        request.addHeader("X-Force-Trace", "true");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { };

        SpanData span = runWithinSpan(() -> filter.doFilter(request, response, chain));

        assertEquals(Boolean.TRUE, span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.booleanKey("force_trace")));
    }

    @Test
    void doesNotTagForceTraceWhenCallerLacksCanTraceRole() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "customer1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
        SpanAttributeEnrichmentFilter filter = new SpanAttributeEnrichmentFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders");
        request.addHeader("X-Force-Trace", "true");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { };

        SpanData span = runWithinSpan(() -> filter.doFilter(request, response, chain));

        assertFalse(span.getAttributes().asMap().keySet().stream()
                .anyMatch(k -> k.getKey().equals("force_trace")));
    }

    private interface FilterAction {
        void run() throws Exception;
    }

    private SpanData runWithinSpan(FilterAction action) throws Exception {
        Span span = tracer.spanBuilder("test-span").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            action.run();
        } finally {
            span.end();
        }
        List<SpanData> spans = exporter.getFinishedSpanItems();
        return spans.get(spans.size() - 1);
    }
}
