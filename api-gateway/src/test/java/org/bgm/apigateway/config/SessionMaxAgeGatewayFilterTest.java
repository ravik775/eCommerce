package org.bgm.apigateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
