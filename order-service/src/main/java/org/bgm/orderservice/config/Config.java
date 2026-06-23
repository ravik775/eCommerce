package org.bgm.orderservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;

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

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
