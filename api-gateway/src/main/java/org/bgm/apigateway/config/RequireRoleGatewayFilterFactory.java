package org.bgm.apigateway.config;

import org.bgm.common.audit.AuditLogger;
import org.bgm.common.correlation.CorrelationConstants;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Edge-level admin gate: rejects a request before it reaches the load
 * balancer/downstream service if the authenticated session doesn't carry
 * one of the required realm roles. Additive to, not a replacement for,
 * each backend service's own @PreAuthorize checks (ADR-0025) — a request
 * that bypasses the gateway entirely (e.g. direct pod-to-pod call in K8s)
 * is still rejected downstream. This filter only saves the round-trip
 * for requests that do go through the gateway.
 *
 * Usage in a route's filters list: `RequireRole=ADMIN` (single role) or
 * `RequireRole=ADMIN,PROVIDER` (any-of, comma-separated — added for
 * Phase 8's provider feature, where a create-product route needs to
 * admit either role and let each backend service's own ownership check
 * do the fine-grained enforcement).
 */
@Component
public class RequireRoleGatewayFilterFactory extends AbstractGatewayFilterFactory<RequireRoleGatewayFilterFactory.Config> {

    public RequireRoleGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public List<String> shortcutFieldOrder() {
        // Without this, the positional shortcut `RequireRole=ADMIN` binds
        // to an auto-generated key ("_genkey_0") instead of the `role`
        // field, leaving config.getRole() null and requiredAuthority
        // permanently "ROLE_null" — found via live testing: every role,
        // including ADMIN, was rejected identically.
        return Collections.singletonList("role");
    }

    @Override
    public GatewayFilter apply(Config config) {
        Set<String> requiredAuthorities = Arrays.stream(config.getRole().split(","))
                .map(String::trim)
                .map(role -> "ROLE_" + role)
                .collect(Collectors.toSet());
        return (exchange, chain) -> ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication().getAuthorities().stream()
                        .anyMatch(a -> requiredAuthorities.contains(a.getAuthority())))
                .defaultIfEmpty(false)
                .flatMap(hasRole -> {
                    if (hasRole) {
                        return chain.filter(exchange);
                    }
                    // Security-relevant denial, not just an ordinary 4xx —
                    // logged on the dedicated AUDIT logger (same one LOGIN/
                    // ORDER_CREATED use) so "who was denied what, when" is
                    // queryable independently of routine app logs, and
                    // taggable by the same correlationId already on the
                    // response (CorrelationTraceGatewayFilter runs first,
                    // HIGHEST_PRECEDENCE, so the header is already set).
                    String correlationId = exchange.getResponse().getHeaders()
                            .getFirst(CorrelationConstants.CORRELATION_ID_HEADER);
                    AuditLogger.log("ACCESS_DENIED", AuditLogger.fields()
                            .with("correlationId", correlationId)
                            .with("path", exchange.getRequest().getPath())
                            .with("method", exchange.getRequest().getMethod())
                            .with("requiredRoles", requiredAuthorities)
                            .build());
                    exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                    return exchange.getResponse().setComplete();
                });
    }

    public static class Config {
        private String role;

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }
    }
}
