package org.bgm.common.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * ADR-0023: reads X-Correlation-Id / X-Trace-Id if present, generates them
 * if absent, puts both in the logging MDC for the duration of the request,
 * and sets them on the response BEFORE calling the rest of the filter
 * chain — so they are present on the response even if a later filter or
 * the controller throws an unhandled exception (success or failure, per
 * ADR-0023's "always echoed" requirement).
 *
 * Auto-registered into every service via CorrelationAutoConfiguration
 * (META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports)
 * — no per-service wiring needed.
 */
public class CorrelationTraceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = firstNonBlank(request.getHeader(CorrelationConstants.CORRELATION_ID_HEADER));
        // Trace ID is meant to be generated once, at the true entry point
        // (the API Gateway). A downstream service only generates its own
        // as a defensive fallback for calls that bypass the gateway.
        String traceId = firstNonBlank(request.getHeader(CorrelationConstants.TRACE_ID_HEADER));

        response.setHeader(CorrelationConstants.CORRELATION_ID_HEADER, correlationId);
        response.setHeader(CorrelationConstants.TRACE_ID_HEADER, traceId);

        MDC.put(CorrelationConstants.MDC_CORRELATION_ID_KEY, correlationId);
        MDC.put(CorrelationConstants.MDC_TRACE_ID_KEY, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationConstants.MDC_CORRELATION_ID_KEY);
            MDC.remove(CorrelationConstants.MDC_TRACE_ID_KEY);
        }
    }

    private static String firstNonBlank(String headerValue) {
        return (headerValue == null || headerValue.isBlank()) ? UUID.randomUUID().toString() : headerValue;
    }
}
