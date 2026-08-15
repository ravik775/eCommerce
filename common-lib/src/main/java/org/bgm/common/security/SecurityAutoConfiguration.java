package org.bgm.common.security;

import org.springframework.boot.actuate.autoconfigure.web.server.ConditionalOnManagementPort;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

/**
 * ADR-0025: registers a {@link JwtAuthenticationConverter} wired with
 * {@link KeycloakRealmRoleConverter} for every service that depends on
 * common-lib and has spring-boot-starter-oauth2-resource-server on the
 * classpath, plus a default {@link SecurityFilterChain} requiring
 * authentication on every request. Discovered via
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports,
 * same mechanism as CorrelationAutoConfiguration (ADR-0023). A service that
 * needs a different policy (e.g., @PreAuthorize role checks on top of this
 * baseline still work fine — @EnableMethodSecurity composes with this
 * filter chain) can define its own SecurityFilterChain bean to override
 * this default (@ConditionalOnMissingBean backs off automatically).
 */
@AutoConfiguration
@ConditionalOnClass(JwtAuthenticationToken.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return converter;
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain defaultResourceServerFilterChain(
            HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
                // Stateless bearer-token API — no browser form/session to
                // protect, so CSRF (a session-cookie-forgery defense) does
                // not apply here.
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }

    /**
     * Phase 7: when {@code management.server.port} differs from the main
     * port (every mTLS-enabled service here), Spring Boot serves
     * actuator from an entirely separate application context — this
     * class's other {@link SecurityFilterChain} bean, registered in the
     * main context, never applies to it at all. Spring Boot Actuator's
     * own auto-configured management security defaults to requiring
     * authentication too, so without an explicit filter chain scoped to
     * that context (via {@code @ConditionalOnManagementPort(DIFFERENT)}
     * + {@link EndpointRequest#toAnyEndpoint()}), Prometheus scraping
     * the management port got 401 regardless of what the main filter
     * chain permitted — found live, the first fix attempt (adding
     * "/actuator/prometheus" to the main chain's permitAll list) had no
     * effect for exactly this reason.
     * <p>
     * Safe to leave wide open (no path restriction beyond "any actuator
     * endpoint"): the management port is plain HTTP but not
     * network-reachable outside the cluster (K8s Service is ClusterIP,
     * NetworkPolicy scopes ingress to the `prometheus` pod specifically
     * on this exact port — see k8s/base/networkpolicy.yaml).
     */
    @Bean
    @Order(0)
    @ConditionalOnManagementPort(ManagementPortType.DIFFERENT)
    public SecurityFilterChain managementSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
