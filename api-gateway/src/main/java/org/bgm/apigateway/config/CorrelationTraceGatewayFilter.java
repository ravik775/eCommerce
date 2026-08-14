package org.bgm.apigateway.config;

import org.bgm.common.correlation.CorrelationConstants;
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

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private static String firstNonBlank(String headerValue) {
        return (headerValue == null || headerValue.isBlank()) ? UUID.randomUUID().toString() : headerValue;
    }
}
