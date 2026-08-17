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
 * <p>
 * Also registers {@link SpanAttributeEnrichmentFilter} — a separate,
 * later-running filter that stamps correlationId/orderId/appTraceId/
 * force_trace onto the same span this class's ForceTraceFilter already
 * runs late enough to tag successfully. Kept as two filter classes rather
 * than merged into one: ForceTraceFilter's job is a security decision
 * (is this caller authorized to force-export), SpanAttributeEnrichmentFilter's
 * is a display concern (make existing MDC values visible on the span) —
 * different reasons to change, same conditions to register under.
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

    // Deliberately ordered Integer.MAX_VALUE — one later (innermost) than
    // forceTraceFilter() above, so it reads MDC only after the controller
    // has fully run (needed for orderId, set mid-controller on the
    // order-creation path — see SpanAttributeEnrichmentFilter's Javadoc).
    @Bean
    public FilterRegistrationBean<SpanAttributeEnrichmentFilter> spanAttributeEnrichmentFilter() {
        FilterRegistrationBean<SpanAttributeEnrichmentFilter> registration =
                new FilterRegistrationBean<>(new SpanAttributeEnrichmentFilter());
        registration.setOrder(Integer.MAX_VALUE);
        registration.addUrlPatterns("/*");
        registration.setName("spanAttributeEnrichmentFilter");
        return registration;
    }
}
