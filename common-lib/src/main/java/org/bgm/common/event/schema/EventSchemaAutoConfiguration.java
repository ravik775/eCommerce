package org.bgm.common.event.schema;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link EventSchemaValidator} as a Spring bean for every service
 * that depends on common-lib. Found missing via live reactor testing —
 * EventSchemaValidator was built in Phase 0 as a plain utility class
 * (`new EventSchemaValidator()`), and nothing wired it into Spring's
 * context until services started constructor-injecting it in Phase 3.
 * Discovered via META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports,
 * same mechanism as CorrelationAutoConfiguration (ADR-0023).
 */
@AutoConfiguration
public class EventSchemaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EventSchemaValidator eventSchemaValidator() {
        return new EventSchemaValidator();
    }
}
