package org.bgm.apigateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.security.Principal;

@Configuration
public class GatewayConfig {

    /**
     * Resolves the rate-limit bucket key by authenticated principal, not
     * client IP — the previous IP-based resolution let every user behind
     * the same NAT/proxy share one bucket, and let a single user rotating
     * IPs evade the limit entirely. Every route this applies to already
     * requires authentication (SecurityConfig), so the IP fallback below
     * only matters for routes explicitly excluded from that requirement.
     */
    @Bean
    KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .switchIfEmpty(Mono.justOrEmpty(exchange.getRequest().getRemoteAddress())
                        .map(addr -> addr.getAddress().getHostAddress())
                        .defaultIfEmpty("anonymous"));
    }
}
