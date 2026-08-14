package org.bgm.apigateway.config;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;

/**
 * Edge-level admin gate: rejects a request before it reaches the load
 * balancer/downstream service if the authenticated session doesn't carry
 * the required realm role. Additive to, not a replacement for, each
 * backend service's own @PreAuthorize checks (ADR-0025) — a request that
 * bypasses the gateway entirely (e.g. direct pod-to-pod call in K8s) is
 * still rejected downstream. This filter only saves the round-trip for
 * requests that do go through the gateway.
 *
 * Usage in a route's filters list: `RequireRole=ADMIN`.
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
        String requiredAuthority = "ROLE_" + config.getRole();
        return (exchange, chain) -> ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication().getAuthorities().stream()
                        .anyMatch(a -> a.getAuthority().equals(requiredAuthority)))
                .defaultIfEmpty(false)
                .flatMap(hasRole -> {
                    if (hasRole) {
                        return chain.filter(exchange);
                    }
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
