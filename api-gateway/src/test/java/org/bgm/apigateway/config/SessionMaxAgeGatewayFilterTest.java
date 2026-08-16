package org.bgm.apigateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0049: direct tests of the staleness decision — same "test the
 * decision logic in isolation, verify the surrounding reactive/session
 * wiring live" split ADR-0048 already established for this gateway's
 * other GlobalFilters (see CorrelationTraceGatewayFilterTest).
 */
class SessionMaxAgeGatewayFilterTest {

    private static final Duration MAX_AGE = Duration.ofMinutes(30);

    @Test
    void sessionYoungerThanMaxAgeIsNotStale() {
        var authentication = oidcAuthenticationIssuedAt(Instant.now().minus(Duration.ofMinutes(10)));

        assertFalse(new SessionMaxAgeGatewayFilter(MAX_AGE).isStale(authentication));
    }

    @Test
    void sessionOlderThanMaxAgeIsStale() {
        var authentication = oidcAuthenticationIssuedAt(Instant.now().minus(Duration.ofMinutes(31)));

        assertTrue(new SessionMaxAgeGatewayFilter(MAX_AGE).isStale(authentication));
    }

    @Test
    void nonOidcAuthenticationIsNeverStale() {
        var authentication = new TestingAuthenticationToken(
                "test-user", "n/a", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));

        assertFalse(new SessionMaxAgeGatewayFilter(MAX_AGE).isStale(authentication));
    }

    @Test
    void nullAuthenticationIsNeverStale() {
        assertFalse(new SessionMaxAgeGatewayFilter(MAX_AGE).isStale(null));
    }

    // Found live (see ADR-0049's incident note): the original
    // map/flatMap/switchIfEmpty shape called chain.filter(exchange) a
    // SECOND time for every request whose first call succeeded, because
    // chain.filter() returns Mono<Void> — which always completes with no
    // emitted value, indistinguishable to switchIfEmpty from "the
    // upstream SecurityContext Mono was empty". This broke every route
    // through the gateway (auth or not) with
    // "ServerHttpResponse already committed" once the downstream chain
    // tried to write to an exchange a second time. This test would have
    // caught it: it asserts the mock chain is invoked exactly once for a
    // non-stale authenticated session.
    @Test
    void chainIsInvokedExactlyOnceForNonStaleSession() {
        Authentication authentication = oidcAuthenticationIssuedAt(Instant.now().minus(Duration.ofMinutes(10)));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/user/me"));
        AtomicInteger invocationCount = new AtomicInteger();
        GatewayFilterChain chain = ex -> {
            invocationCount.incrementAndGet();
            return Mono.empty();
        };

        new SessionMaxAgeGatewayFilter(MAX_AGE).filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                        Mono.just(new SecurityContextImpl(authentication))))
                .block();

        assertEquals(1, invocationCount.get(), "chain.filter(exchange) must run exactly once per request");
    }

    @Test
    void chainIsInvokedOnceWhenNoSecurityContextPresent() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health"));
        AtomicInteger invocationCount = new AtomicInteger();
        GatewayFilterChain chain = ex -> {
            invocationCount.incrementAndGet();
            return Mono.empty();
        };

        new SessionMaxAgeGatewayFilter(MAX_AGE).filter(exchange, chain).block();

        assertEquals(1, invocationCount.get(),
                "an exchange with no security context at all (e.g. a permitted path) must still proceed exactly once");
    }

    // Mirrors production shape: the session's Authentication is an
    // OAuth2AuthenticationToken whose principal is the OidcUser, not an
    // OidcUser directly (see isStale's Javadoc for why this distinction
    // mattered).
    private Authentication oidcAuthenticationIssuedAt(Instant issuedAt) {
        OidcIdToken idToken = new OidcIdToken(
                "test-token-value", issuedAt, issuedAt.plus(Duration.ofHours(1)),
                Map.of("sub", "test-subject"));
        DefaultOidcUser oidcUser =
                new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")), idToken);
        return new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "keycloak");
    }
}
