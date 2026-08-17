package org.bgm.apigateway.config;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.bgm.common.audit.AuditLogger;
import org.bgm.common.correlation.CorrelationConstants;
import org.bgm.common.tracing.ErrorAlwaysSampledSpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
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
 * <p>
 * ADR-0052 (2026-08-17): X-Trace-Id is now UNCONDITIONALLY generated here
 * — a client-supplied value is never honored, unlike X-Correlation-Id.
 * Found live: the UI generated its own random X-Trace-Id client-side
 * (fixed per browser tab) and the gateway trusted it verbatim via the
 * same firstNonBlank fallback X-Correlation-Id still legitimately uses.
 * That's a real gap for a value this project treats as an authoritative
 * cross-service correlation anchor — a client can set it to anything,
 * including a fabricated or reused value, which is exactly the kind of
 * input a security-relevant investigation identifier must not trust.
 * X-Correlation-Id keeps the old behavior deliberately: it's closer to
 * an idempotency-style client-supplied token (same accepted pattern as
 * Idempotency-Key elsewhere in this codebase), not the thing this system
 * treats as the ground-truth trace anchor.
 */
@Component
public class CorrelationTraceGatewayFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(CorrelationTraceGatewayFilter.class);

    // ADR-0048: same fix as common-lib's ForceTraceFilter, reactive
    // equivalent — found live, a request with a manually-added
    // X-Force-Trace header still force-exported the gateway's own span
    // regardless of the caller's actual roles. ReactiveSecurityContextHolder,
    // not SecurityContextHolder (WebFlux keeps the security context in
    // Reactor Context, not a ThreadLocal, so the synchronous holder is
    // always empty here) — same ROLE_CAN_TRACE authority
    // KeycloakOidcUserService already maps onto every OidcUser.
    private static final String CAN_TRACE_AUTHORITY = "ROLE_CAN_TRACE";

    // Found live: a checkout request's OTel trace ID on the gateway's own
    // span (e.g. "006d2268...") never matched order-service's span for
    // the SAME request (e.g. "7b5b4cb3...") — proof the distributed trace
    // tree was never actually one tree, just two independently-rooted
    // ones per hop. Root cause: this filter previously ran at
    // Ordered.HIGHEST_PRECEDENCE, before WebFlux's own HTTP-server
    // observation instrumentation had created this request's span —
    // Span.current()/Context.current() saw a no-op span at that point
    // (same bug shape as ForceTraceFilter's original ordering mistake on
    // the servlet side), AND, independent of that, nothing was ever
    // injecting a W3C traceparent header onto the proxied outbound
    // request — Spring Cloud Gateway's NettyRoutingFilter does not do
    // this automatically for a raw reactor-netty routing call the way
    // WebClient-based calls get instrumented. Injecting it explicitly
    // here, and moving this filter's order late (see getOrder()) so the
    // injected context is the real one, fixes both.
    private static final TextMapSetter<ServerHttpRequest.Builder> TRACEPARENT_SETTER =
            (carrier, key, value) -> {
                if (carrier != null) {
                    carrier.header(key, value);
                }
            };

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        String correlationId = firstNonBlank(
                request.getHeaders().getFirst(CorrelationConstants.CORRELATION_ID_HEADER));
        // ADR-0052: always generated here, never read from the incoming
        // request — see the class Javadoc for why this differs from
        // correlationId's client-honoring behavior above.
        String traceId = UUID.randomUUID().toString();

        ServerHttpRequest.Builder requestBuilder = request.mutate()
                .header(CorrelationConstants.CORRELATION_ID_HEADER, correlationId)
                .header(CorrelationConstants.TRACE_ID_HEADER, traceId);

        // Links the backend's span to the gateway's span as its real
        // parent — this is what actually makes Tempo present one tree
        // across services instead of a separate root per hop. Static
        // W3CTraceContextPropagator, not a Spring-registered
        // ContextPropagators bean: Spring Boot's OTel autoconfiguration
        // doesn't reliably expose one as an injectable bean across
        // versions, and the W3C format is standard regardless of how the
        // SDK is wired — every backend's own Micrometer/OTel HTTP-server
        // instrumentation already extracts "traceparent" automatically.
        W3CTraceContextPropagator.getInstance().inject(Context.current(), requestBuilder, TRACEPARENT_SETTER);

        ServerHttpRequest mutatedRequest = requestBuilder.build();

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
        Mono<Void> proceed = chain.filter(exchange.mutate().request(mutatedRequest).build())
                .doOnSuccess(v -> logIfError(exchange, correlationId, traceId, null))
                .doOnError(ex -> logIfError(exchange, correlationId, traceId, ex));

        // ADR-0048: the header alone is not authorization — verified
        // against the caller's actual roles (ReactiveSecurityContextHolder,
        // not the synchronous SecurityContextHolder, since WebFlux keeps
        // the security context in Reactor Context, not a ThreadLocal)
        // before stamping the gateway's own span. If there's no security
        // context (shouldn't happen here — everything past this filter
        // requires authentication — but fails closed either way), the
        // attribute is simply never set and the request proceeds
        // normally.
        if (!"true".equalsIgnoreCase(request.getHeaders().getFirst("X-Force-Trace"))) {
            return proceed;
        }
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .doOnNext(authentication -> {
                    if (callerHasCanTraceRole(authentication)) {
                        Span.current().setAttribute(ErrorAlwaysSampledSpanExporter.FORCE_TRACE_ATTRIBUTE, true);
                    } else {
                        auditDenied(authentication, exchange);
                    }
                })
                .then(proceed);
    }

    // ADR-0048 (2026-08-16 update): same rationale as ForceTraceFilter's
    // servlet-side audit line — a silent fail-closed denial left no
    // trail to filter/alert on. Same AuditLogger, same event name, so
    // both the gateway hop and every downstream servlet hop of a single
    // denied attempt are findable under one auditEvent grep.
    private void auditDenied(Authentication authentication, ServerWebExchange exchange) {
        String principal = authentication != null ? String.valueOf(authentication.getName()) : "anonymous";
        AuditLogger.log("FORCE_TRACE_DENIED", AuditLogger.fields()
                .with("principal", principal)
                .with("path", exchange.getRequest().getPath().value())
                .build());
    }

    // Package-private (not private): unit-tested directly in
    // CorrelationTraceGatewayFilterTest — the decision logic itself,
    // isolated from the surrounding reactive/OTel-context wiring, which
    // is verified separately by live testing (see ADR-0048).
    boolean callerHasCanTraceRole(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (CAN_TRACE_AUTHORITY.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
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
        // Deliberately LATE (see the TRACEPARENT_SETTER Javadoc above for
        // why): Spring Cloud Gateway's NettyRoutingFilter — the filter
        // that actually issues the proxied call — runs at
        // Ordered.LOWEST_PRECEDENCE, so running one step ahead of it
        // guarantees this filter executes after WebFlux's own span has
        // been created (Span.current()/Context.current() are valid) while
        // still mutating the exchange in time for NettyRoutingFilter to
        // see the injected traceparent header and the correlation/trace
        // ID headers on its outbound call.
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    private static String firstNonBlank(String headerValue) {
        return (headerValue == null || headerValue.isBlank()) ? UUID.randomUUID().toString() : headerValue;
    }
}
