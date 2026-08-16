package org.bgm.common.tracing;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("true".equalsIgnoreCase(request.getHeader(FORCE_TRACE_HEADER))) {
            Span.current().setAttribute(ErrorAlwaysSampledSpanExporter.FORCE_TRACE_ATTRIBUTE, true);
        }
        chain.doFilter(request, response);
    }
}
