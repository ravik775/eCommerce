package org.bgm.otelext;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * ADR-0064: the same export-time sampling policy as common-lib's
 * ErrorAlwaysSampledSpanExporter (successful spans sampled at a
 * configurable rate; error spans and {@code force_trace}-attributed
 * spans always exported), re-implemented here rather than reused,
 * because this class runs inside the OpenTelemetry Java agent's own
 * isolated extension classloader — pulling in common-lib (and
 * transitively Spring, Jakarta Servlet, Spring Cloud Gateway) here would
 * be both unnecessary and a real classloading-conflict risk against the
 * agent's own shaded dependencies. Deliberate duplication of ~30 lines,
 * not a refactor opportunity: keeping this module's only dependency
 * surface to the OTel SDK's own autoconfigure-SPI is the whole point of
 * it being a separate module in the first place.
 * <p>
 * Registered via
 * {@code META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider}
 * and loaded by the agent via
 * {@code -Dotel.javaagent.extensions=/otel/otel-agent-extension.jar}.
 * Requires {@code OTEL_TRACES_SAMPLER=always_on} (agent-side config, see
 * api-gateway.yaml) for the same reason common-lib's exporter needs
 * common-lib's TracingAutoConfiguration to pair it with AlwaysOnSampler
 * — every span must actually be recorded before this filter has
 * anything to inspect at export time.
 */
public class ForceTraceAndErrorSamplingExtension implements AutoConfigurationCustomizerProvider {

    // Deliberately the same literal key name common-lib's gateway filter
    // stamps via ErrorAlwaysSampledSpanExporter.FORCE_TRACE_ATTRIBUTE
    // ("force_trace") — the two live in different classloaders/modules
    // and can't share the AttributeKey constant, but OTel attributes are
    // matched by key name + type at export time, not by object identity,
    // so this only needs to match by value, not by reference.
    private static final AttributeKey<Boolean> FORCE_TRACE_ATTRIBUTE = AttributeKey.booleanKey("force_trace");

    private static final AttributeKey<Long> HTTP_STATUS_CODE = AttributeKey.longKey("http.response.status_code");
    private static final AttributeKey<Long> HTTP_STATUS_CODE_LEGACY = AttributeKey.longKey("http.status_code");
    private static final AttributeKey<String> HTTP_STATUS_CODE_STR = AttributeKey.stringKey("http.response.status_code");
    private static final AttributeKey<String> HTTP_STATUS_CODE_LEGACY_STR = AttributeKey.stringKey("http.status_code");

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        // ADR-0064: this registration call itself is trivial, untested
        // framework glue — deliberately not exercised by a unit test,
        // since doing so means hand-implementing the whole
        // AutoConfigurationCustomizer SPI interface, which has proven
        // fragile across OTel SDK versions (this module's build broke
        // twice on an anonymous test double missing a newly-added
        // abstract method — addPropertiesSupplier, then
        // addSamplerCustomizer — neither used by this class at all).
        // ErrorAlwaysSampledDelegatingExporter (the actual filtering
        // policy) is the part with real logic worth testing, and is
        // tested directly below, independent of this SPI surface.
        autoConfiguration.addSpanExporterCustomizer((delegate, configProperties) -> {
            double sampleRate = configProperties.getDouble("otel.traces.success.sample.rate", 0.1);
            return new ErrorAlwaysSampledDelegatingExporter(delegate, sampleRate);
        });
    }

    // Package-visible (not private/nested-only) specifically so the test
    // can construct and exercise it directly, without going through
    // AutoConfigurationCustomizer at all.
    static final class ErrorAlwaysSampledDelegatingExporter implements SpanExporter {
        private final SpanExporter delegate;
        private final double sampleRate;

        ErrorAlwaysSampledDelegatingExporter(SpanExporter delegate, double sampleRate) {
            this.delegate = delegate;
            this.sampleRate = sampleRate;
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            List<SpanData> toExport = spans.stream()
                    .filter(this::shouldExport)
                    .collect(Collectors.toList());
            if (toExport.isEmpty()) {
                return CompletableResultCode.ofSuccess();
            }
            return delegate.export(toExport);
        }

        private boolean shouldExport(SpanData span) {
            if (span.getStatus().getStatusCode() == StatusData.error().getStatusCode()) {
                return true;
            }
            if (isHttpError(span)) {
                return true;
            }
            if (Boolean.TRUE.equals(span.getAttributes().get(FORCE_TRACE_ATTRIBUTE))) {
                return true;
            }
            return ThreadLocalRandom.current().nextDouble() < sampleRate;
        }

        private boolean isHttpError(SpanData span) {
            Long status = span.getAttributes().get(HTTP_STATUS_CODE);
            if (status == null) {
                status = span.getAttributes().get(HTTP_STATUS_CODE_LEGACY);
            }
            if (status != null) {
                return status >= 400;
            }
            String statusStr = span.getAttributes().get(HTTP_STATUS_CODE_STR);
            if (statusStr == null) {
                statusStr = span.getAttributes().get(HTTP_STATUS_CODE_LEGACY_STR);
            }
            if (statusStr != null) {
                try {
                    return Long.parseLong(statusStr) >= 400;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
            return false;
        }

        @Override
        public CompletableResultCode flush() {
            return delegate.flush();
        }

        @Override
        public CompletableResultCode shutdown() {
            return delegate.shutdown();
        }
    }
}
