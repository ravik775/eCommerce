package org.bgm.orderservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

@Configuration
public class Config {

    @Bean
    public AuditorAware<String> auditorProvider(){
        return ()->{
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if(auth!= null &&
                    auth.isAuthenticated() &&
                    !(auth instanceof AnonymousAuthenticationToken)){
                var name = auth.getName();
                if(name!=null || !name.isBlank())
                    return Optional.of(name);
            }
            return Optional.of("SYSTEM");
        };
    }

    // ADR-0001 (doc/adr/ADR-0001-idp-keycloak.md) and ADR-0005
    // (doc/adr/ADR-0005-api-gateway-boundary.md): real JWT resource-server
    // security lands here in Phase 4, once Keycloak issues tokens to
    // validate. Until then, no SecurityFilterChain bean is defined and
    // spring-boot-starter-security is not on the classpath (see pom.xml) —
    // this is deliberate: a permitAll() stub was removed rather than left
    // in place, so there is no silently-disabled security to forget about.
}
