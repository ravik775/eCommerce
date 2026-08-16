package org.bgm.apigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeReactiveAuthenticationManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.security.web.server.SecurityWebFilterChain;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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

    // Browser-reachable, matching provider.keycloak.authorization-uri
    // (see api-gateway.yml's comment on why issuer-uri/OIDC discovery
    // isn't used here — a fetch target can't be both container- and
    // browser-reachable on this network topology, so every endpoint is
    // wired manually instead). Spring's ClientRegistration only learns
    // an end_session_endpoint via OIDC discovery, so
    // OidcClientInitiatedServerLogoutSuccessHandler has nothing to read
    // here and silently no-ops into a local-only logout — found live
    // (POST /logout returned straight to /login?logout with no Keycloak
    // round-trip at all, leaving its SSO session alive). This property
    // sidesteps that by building the logout URL by hand, same manual
    // pattern as the other four endpoints.
    private final String keycloakLogoutUri;

    public SecurityConfig(
            KeycloakOidcUserService keycloakOidcUserService,
            @Value("${keycloak.logout-uri:http://localhost:8090/realms/ecom/protocol/openid-connect/logout}") String keycloakLogoutUri) {
        this.keycloakOidcUserService = keycloakOidcUserService;
        this.keycloakLogoutUri = keycloakLogoutUri;
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
                        // Phase 7: Prometheus scrapes this gateway's own
                        // /actuator/prometheus directly (no separate
                        // management port needed here — the gateway
                        // never enforced inbound mTLS to begin with).
                        // Without this exclusion, the scrape request hit
                        // the OAuth2 login redirect instead of metrics —
                        // found live via Prometheus's scrape error log.
                        .pathMatchers("/actuator/health", "/actuator/prometheus").permitAll()
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
                .oauth2Client(oauth2Client -> oauth2Client.authorizationRequestResolver(resolver))
                // Found live: without an explicit logout-success handler,
                // Spring Security's default /logout only invalidates the
                // gateway's own session — Keycloak's SSO session (the
                // KEYCLOAK_SESSION cookie on localhost:8090) stays alive,
                // so the very next login silently re-authenticates as
                // whoever was previously signed in instead of showing a
                // credentials form. This RP-initiated logout redirects to
                // Keycloak's end_session_endpoint (killing the SSO
                // session) before coming back here. Requires
                // post.logout.redirect.uris to be registered on the
                // ecommerce-gateway Keycloak client (see
                // ecom-realm.json.template) or Keycloak rejects the
                // redirect with "Invalid redirect uri".
                .logout(logout -> logout.logoutSuccessHandler(oidcLogoutSuccessHandler()));
        return http.build();
    }

    private ServerLogoutSuccessHandler oidcLogoutSuccessHandler() {
        return (WebFilterExchange exchange, Authentication authentication) -> {
            String idTokenHint = null;
            if (authentication != null && authentication.getPrincipal() instanceof OidcUser oidcUser) {
                idTokenHint = oidcUser.getIdToken().getTokenValue();
            }
            URI requestUri = exchange.getExchange().getRequest().getURI();
            String postLogoutRedirectUri = requestUri.getScheme() + "://" + requestUri.getAuthority();

            StringBuilder location = new StringBuilder(keycloakLogoutUri)
                    .append("?client_id=ecommerce-gateway")
                    .append("&post_logout_redirect_uri=")
                    .append(URLEncoder.encode(postLogoutRedirectUri, StandardCharsets.UTF_8));
            if (idTokenHint != null) {
                location.append("&id_token_hint=").append(idTokenHint);
            }

            exchange.getExchange().getResponse().setStatusCode(HttpStatus.FOUND);
            exchange.getExchange().getResponse().getHeaders().setLocation(URI.create(location.toString()));
            return exchange.getExchange().getResponse().setComplete();
        };
    }
}
