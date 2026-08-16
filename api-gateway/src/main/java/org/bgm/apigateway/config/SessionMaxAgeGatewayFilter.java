package org.bgm.apigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

/**
 * ADR-0049 (doc/adr/ADR-0049-gateway-session-absolute-max-age.md): closes
 * the gap where a Keycloak role grant/revocation made to an already-logged-in
 * user never reaches their gateway session. {@link KeycloakOidcUserService}
 * only maps {@code realm_access.roles} into authorities once, at initial
 * OAuth2 login — Spring then caches that {@link OidcUser} (authorities
 * baked in) in the server-side session for as long as the session lives.
 * Inactivity timeout alone doesn't bound this: a continuously-active
 * session's stale authority snapshot can persist indefinitely.
 * <p>
 * This filter forces re-authentication once the session's ID token is
 * older than {@code security.session.max-age} (default 30 minutes),
 * regardless of activity. Because the underlying Keycloak SSO session
 * (KEYCLOAK_SESSION cookie) is untouched, the forced re-login completes
 * silently — same mechanism {@link SecurityConfig}'s
 * {@code oauth2LoginFailureHandler} relies on for the "already logged in"
 * case: no local session + valid Keycloak SSO session = transparent
 * re-authentication, and {@link KeycloakOidcUserService#loadUser} runs
 * again, picking up whatever roles Keycloak currently has on file.
 */
@Component
public class SessionMaxAgeGatewayFilter implements GlobalFilter, Ordered {

    private final Duration maxAge;

    public SessionMaxAgeGatewayFilter(
            @Value("${security.session.max-age:PT30M}") Duration maxAge) {
        this.maxAge = maxAge;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .flatMap(authentication -> isStale(authentication)
                        ? forceReauth(exchange)
                        : chain.filter(exchange))
                // No security context (e.g. the permitted actuator paths) —
                // nothing to bound, proceed normally.
                .switchIfEmpty(Mono.defer(() -> chain.filter(exchange)));
    }

    // Package-private (not private): unit-tested directly in
    // SessionMaxAgeGatewayFilterTest — see ADR-0049's regression-guard
    // section for why the surrounding reactive/session wiring is
    // verified live instead.
    boolean isStale(Authentication authentication) {
        // The session's Authentication is an OAuth2AuthenticationToken
        // whose *principal* is the OidcUser — not an OidcUser itself
        // (found while writing SessionMaxAgeGatewayFilterTest: an
        // `authentication instanceof OidcUser` check here is always
        // false in production, since Spring's OAuth2 login sets the
        // token type, not the user object, as the Authentication).
        if (authentication == null || !(authentication.getPrincipal() instanceof OidcUser oidcUser)) {
            return false;
        }
        Instant issuedAt = oidcUser.getIdToken().getIssuedAt();
        return issuedAt != null && Duration.between(issuedAt, Instant.now()).compareTo(maxAge) > 0;
    }

    // Invalidates the gateway's own session only — the Keycloak SSO
    // session is deliberately left alone (see class Javadoc) so the
    // redirect below re-authenticates transparently instead of forcing
    // the user through a login form again.
    private Mono<Void> forceReauth(ServerWebExchange exchange) {
        return exchange.getSession()
                .flatMap(session -> session.invalidate())
                .then(Mono.defer(() -> {
                    exchange.getResponse().setStatusCode(HttpStatus.FOUND);
                    exchange.getResponse().getHeaders().setLocation(
                            URI.create(exchange.getRequest().getPath().value()));
                    return exchange.getResponse().setComplete();
                }));
    }

    @Override
    public int getOrder() {
        // Early: no reason to do correlation/trace-header work (see
        // CorrelationTraceGatewayFilter, which runs deliberately late)
        // for a request this filter is about to redirect anyway.
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
