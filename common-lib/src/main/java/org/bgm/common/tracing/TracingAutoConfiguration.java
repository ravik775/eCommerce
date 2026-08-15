package org.bgm.common.tracing;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * ADR-0032: registers the error-always-sampled OTLP export pipeline into
 * every service that depends on common-lib, same auto-registration
 * mechanism as CorrelationAutoConfiguration/AuditAutoConfiguration.
 * <p>
 * {@code management.tracing.enabled=false} (the default here — see
 * common-config) fully disables this: a service that hasn't opted in
 * pays no tracing overhead at all. Enabled per-environment via the
 * {@code TRACING_ENABLED} env var once Tempo is confirmed reachable.
 */
@AutoConfiguration
@ConditionalOnClass(SpanExporter.class)
@ConditionalOnProperty(value = "management.tracing.enabled", havingValue = "true")
public class TracingAutoConfiguration {

    // AlwaysOnSampler, deliberately NOT the probability-based sampler
    // Spring Boot would otherwise wire from management.tracing.sampling.probability:
    // ErrorAlwaysSampledSpanExporter needs every span actually recorded
    // so it can inspect status at export time (see its Javadoc for why
    // this can't be a head-sampling decision). The real sample rate for
    // successful spans lives in tracing.success-sample-rate instead.
    @Bean
    @ConditionalOnMissingBean(Sampler.class)
    public Sampler alwaysOnSampler() {
        return Sampler.alwaysOn();
    }

    @Bean
    @Primary
    public SpanExporter errorAlwaysSampledSpanExporter(
            @Value("${management.otlp.tracing.endpoint}") String otlpEndpoint,
            @Value("${tracing.success-sample-rate:0.1}") double successSampleRate) {
        SpanExporter otlpExporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(otlpEndpoint)
                .setTimeout(Duration.ofSeconds(10))
                .build();
        return new ErrorAlwaysSampledSpanExporter(otlpExporter, successSampleRate);
    }
}
