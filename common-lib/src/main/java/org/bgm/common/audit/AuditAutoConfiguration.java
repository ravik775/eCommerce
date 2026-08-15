package org.bgm.common.audit;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.AccessDeniedException;

/**
 * Registers {@link AccessDeniedAuditAdvice} into every Servlet-based
 * service that depends on common-lib, same mechanism as
 * CorrelationAutoConfiguration (ADR-0023) — a plain {@code @RestControllerAdvice}
 * in common-lib is NOT picked up by a consuming service's component scan
 * (different base package), so it must be registered explicitly here.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(AccessDeniedException.class)
public class AuditAutoConfiguration {

    @Bean
    public AccessDeniedAuditAdvice accessDeniedAuditAdvice() {
        return new AccessDeniedAuditAdvice();
    }
}
