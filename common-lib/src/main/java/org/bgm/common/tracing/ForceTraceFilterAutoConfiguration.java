package org.bgm.common.tracing;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * ADR-0032: registers {@link ForceTraceFilter} into every Servlet-based
 * service that depends on common-lib. Split out from
 * {@link TracingAutoConfiguration} into its own class — see that class's
 * Javadoc for why a servlet-only {@code @Bean} method living alongside
 * WebFlux-compatible ones broke the (WebFlux) gateway even though the
 * method's own condition would have skipped it there.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(jakarta.servlet.Filter.class)
@ConditionalOnProperty(value = "management.tracing.enabled", havingValue = "true")
public class ForceTraceFilterAutoConfiguration {

    @Bean
    public FilterRegistrationBean<ForceTraceFilter> forceTraceFilter() {
        FilterRegistrationBean<ForceTraceFilter> registration = new FilterRegistrationBean<>(new ForceTraceFilter());
        // Deliberately LATE, not early like CorrelationTraceFilter's
        // MDC-based approach: found live, running this near the front of
        // the chain (originally Integer.MIN_VALUE + 1) meant
        // Span.current() saw no active span yet — Spring's own HTTP
        // tracing instrumentation hadn't created the request's span at
        // that point in the chain — so setAttribute() was a silent
        // no-op on an invalid span, and the real exported span never
        // got tagged. Running near LOWEST_PRECEDENCE instead means
        // Spring's instrumentation has already established the real
        // span by the time this filter executes; attributes can be set
        // any time before a span ends, so being late costs nothing here.
        registration.setOrder(Integer.MAX_VALUE - 1);
        registration.addUrlPatterns("/*");
        registration.setName("forceTraceFilter");
        return registration;
    }
}
