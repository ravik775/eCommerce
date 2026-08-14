package org.bgm.apigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeReactiveAuthenticationManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * ADR-0005 (doc/adr/ADR-0005-api-gateway-boundary.md): the gateway
 * terminates the browser-facing OIDC login (Authorization Code + PKCE,
 * ADR-0017) — everything except health checks requires an authenticated
 * session. The resulting access token is relayed downstream via the
 * TokenRelay filter (config-server/config-repo/api-gateway.yml), where
 * every backend service independently validates it (ADR-0025) — the
 * gateway is not the sole authorization point.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private final KeycloakOidcUserService keycloakOidcUserService;

    public SecurityConfig(KeycloakOidcUserService keycloakOidcUserService) {
        this.keycloakOidcUserService = keycloakOidcUserService;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http, ReactiveClientRegistrationRepository clientRegistrationRepository) {
        // Keycloak's ecommerce-gateway client mandates PKCE
        // (pkce.code.challenge.method=S256, ADR-0017) even though it's a
        // confidential client. Spring's default authorization-request
        // resolver only attaches PKCE params for public clients
        // (client-authentication-method=none), so a confidential client's
        // login otherwise fails at Keycloak with "Missing parameter:
        // code_challenge_method" — found via live login-flow testing.
        // Forcing withPkce() here applies it regardless of client type.
        DefaultServerOAuth2AuthorizationRequestResolver resolver =
                new DefaultServerOAuth2AuthorizationRequestResolver(clientRegistrationRepository);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/actuator/health").permitAll()
                        .anyExchange().authenticated())
                // Maps realm_access.roles onto the session's OidcUser (see
                // KeycloakOidcUserService) so RequireRoleGatewayFilterFactory
                // can reject admin-only routes by role at the edge. Reactive
                // OAuth2LoginSpec has no userInfoEndpoint()/oidcUserService()
                // shortcut (unlike the servlet DSL) — the custom user service
                // is wired via a custom authentication manager instead.
                .oauth2Login(oauth2 -> oauth2
                        .authorizationRequestResolver(resolver)
                        .authenticationManager(
                                new OidcAuthorizationCodeReactiveAuthenticationManager(
                                        new WebClientReactiveAuthorizationCodeTokenResponseClient(),
                                        keycloakOidcUserService)))
                .oauth2Client(oauth2Client -> oauth2Client.authorizationRequestResolver(resolver));
        return http.build();
    }
}
