package org.bgm.common.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
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
}
