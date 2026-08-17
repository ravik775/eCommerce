package org.bgm.common.tracing;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.bgm.common.correlation.CorrelationConstants;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Stamps {@code correlationId}, {@code orderId}, {@code appTraceId} (MDC
 * fields — see CorrelationConstants) and {@code force_trace} onto the
 * currently exported OTel span as attributes, so they're visible directly
 * in a Tempo trace instead of only in Loki log lines. Found live: a
 * Grafana trace for a checkout request showed only the default HTTP
 * instrumentation attributes (method/uri/status/outcome/exception) — MDC
 * and the OTel span are two independent systems that happen to share
 * values, and nothing was ever writing those values onto the span itself.
 * <p>
 * Runs in the "after chain.doFilter()" phase, not before, and is
 * registered at {@code Integer.MAX_VALUE} — the innermost/deepest filter
 * in the chain (later than {@link ForceTraceFilter}'s
 * {@code Integer.MAX_VALUE - 1}) — so it reads MDC only after the
 * controller has fully run. This matters specifically for orderId: on the
 * order-creation request, order-service's OrderService only puts
 * orderId into MDC once the order row is persisted and its ID is known,
 * partway through the controller call — reading MDC any earlier (e.g. in
 * a pre-chain block, the way ForceTraceFilter reads its header) would
 * miss it. Same {@code Span.current()} validity reasoning as
 * ForceTraceFilter's Javadoc: Spring's own HTTP tracing instrumentation
 * has already created the real span for the request by the time any
 * filter this late in the chain runs, in either direction.
 * <p>
 * Attributes are only set when the corresponding MDC value is present and
 * non-blank — an absent field (e.g. no orderId on a catalog-browsing
 * request) is simply omitted from the span rather than exported as an
 * empty/"null" string.
 */
public class SpanAttributeEnrichmentFilter extends OncePerRequestFilter {

    static final String ATTR_CORRELATION_ID = "correlationId";
    static final String ATTR_ORDER_ID = "orderId";
    static final String ATTR_APP_TRACE_ID = "appTraceId";
    static final String ATTR_FORCE_TRACE = "force_trace";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            enrichCurrentSpan(request);
        }
    }

    private void enrichCurrentSpan(HttpServletRequest request) {
        Span span = Span.current();
        setIfPresent(span, ATTR_CORRELATION_ID, MDC.get(CorrelationConstants.MDC_CORRELATION_ID_KEY));
        setIfPresent(span, ATTR_ORDER_ID, MDC.get(CorrelationConstants.MDC_ORDER_ID_KEY));
        setIfPresent(span, ATTR_APP_TRACE_ID, MDC.get(CorrelationConstants.MDC_TRACE_ID_KEY));
        // Independently recomputed via the shared, package-private
        // ForceTraceFilter.callerHasCanTraceRole() check (same
        // authorization decision, single source of truth) rather than
        // read back from the span — the OTel API is write-only for
        // attributes set earlier in the same request.
        boolean forceTraceRequested = "true".equalsIgnoreCase(request.getHeader(ForceTraceFilter.FORCE_TRACE_HEADER))
                && ForceTraceFilter.callerHasCanTraceRole();
        if (forceTraceRequested) {
            span.setAttribute(ATTR_FORCE_TRACE, true);
        }
    }

    private static void setIfPresent(Span span, String key, String value) {
        if (value != null && !value.isBlank()) {
            span.setAttribute(key, value);
        }
    }
}
