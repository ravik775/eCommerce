package org.bgm.apigateway.config;

import org.bgm.common.correlation.CorrelationConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * ADR-0023 (doc/adr/ADR-0023-correlation-trace-id.md): the gateway is the
 * system's true entry point, so this is where X-Trace-Id is normally
 * generated (downstream services only generate their own defensively, for
 * calls that bypass the gateway — see common-lib's CorrelationTraceFilter).
 * X-Correlation-Id is generated here too if the caller didn't supply one.
 * Both are always echoed on the response, success or failure.
 */
@Component
public class CorrelationTraceGatewayFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(CorrelationTraceGatewayFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        String correlationId = firstNonBlank(
                request.getHeaders().getFirst(CorrelationConstants.CORRELATION_ID_HEADER));
        String traceId = firstNonBlank(
                request.getHeaders().getFirst(CorrelationConstants.TRACE_ID_HEADER));

        ServerHttpRequest mutatedRequest = request.mutate()
                .header(CorrelationConstants.CORRELATION_ID_HEADER, correlationId)
                .header(CorrelationConstants.TRACE_ID_HEADER, traceId)
                .build();

        // Set on the response up front so both headers are present even if
        // a downstream call fails or the gateway itself errors out.
        exchange.getResponse().getHeaders().set(CorrelationConstants.CORRELATION_ID_HEADER, correlationId);
        exchange.getResponse().getHeaders().set(CorrelationConstants.TRACE_ID_HEADER, traceId);

        // Gateway is WebFlux (reactive) — MDC doesn't survive a thread
        // hop between operators the way it does in the servlet backends'
        // CorrelationTraceFilter, so a request handled/failed entirely
        // inside the gateway (a circuit-breaker fallback, a 403 from
        // RequireRoleGatewayFilterFactory) would otherwise leave no
        // grep-able trail here. One explicit log line on non-2xx keeps
        // "find the exception" (see doc/architecture — Grafana/Loki
        // walkthrough) working for gateway-originated failures, not just
        // ones a downstream servlet service produced.
        // A downstream servlet backend's own CorrelationTraceFilter also
        // echoes these same two headers on ITS response; Spring Cloud
        // Gateway's routing filter merges that onto this exchange's
        // response by ADDING rather than replacing — found live as a
        // literal "id, id" duplicate on the browser-visible header. The
        // RemoveResponseHeader filter (each route's default-filters,
        // gateway-routes ConfigMap) strips the downstream copy before
        // the merge lands, so the single value set above survives as
        // the only one. Fixing it here (post-response, by re-setting)
        // isn't reliable — by the time this Mono completes the response
        // may already be committed/flushed.
        return chain.filter(exchange.mutate().request(mutatedRequest).build())
                .doOnSuccess(v -> logIfError(exchange, correlationId, traceId, null))
                .doOnError(ex -> logIfError(exchange, correlationId, traceId, ex));
    }

    private void logIfError(ServerWebExchange exchange, String correlationId, String traceId, Throwable ex) {
        int status = exchange.getResponse().getStatusCode() != null
                ? exchange.getResponse().getStatusCode().value() : 0;
        if (ex != null) {
            log.warn("[correlationId={},traceId={}] gateway request failed: {} {} -> {}",
                    correlationId, traceId, exchange.getRequest().getMethod(), exchange.getRequest().getPath(), ex.toString());
        } else if (status >= 400) {
            log.warn("[correlationId={},traceId={}] gateway request non-2xx: {} {} -> {}",
                    correlationId, traceId, exchange.getRequest().getMethod(), exchange.getRequest().getPath(), status);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private static String firstNonBlank(String headerValue) {
        return (headerValue == null || headerValue.isBlank()) ? UUID.randomUUID().toString() : headerValue;
    }
}
