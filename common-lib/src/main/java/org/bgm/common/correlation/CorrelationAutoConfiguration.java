package org.bgm.common.correlation;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * ADR-0023: registers {@link CorrelationTraceFilter} into every Servlet-
 * based service that depends on common-lib, with no per-service wiring.
 * Discovered via META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
 * (Spring Boot 3's autoconfiguration registration mechanism).
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(jakarta.servlet.Filter.class)
public class CorrelationAutoConfiguration {

    @Bean
    public FilterRegistrationBean<CorrelationTraceFilter> correlationTraceFilter() {
        FilterRegistrationBean<CorrelationTraceFilter> registration =
                new FilterRegistrationBean<>(new CorrelationTraceFilter());
        registration.setOrder(Integer.MIN_VALUE); // run first, before anything else sees the request
        registration.addUrlPatterns("/*");
        registration.setName("correlationTraceFilter");
        return registration;
    }
}
