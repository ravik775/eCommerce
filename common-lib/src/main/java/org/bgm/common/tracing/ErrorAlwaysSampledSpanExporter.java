package org.bgm.common.tracing;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * ADR-0032's sampling policy: successful spans are sampled at a
 * configurable rate; every span that ended in an error is always
 * exported, regardless of that rate. This has to be implemented as an
 * export-time filter, not the OTel {@code Sampler} interface — a
 * {@code Sampler}'s decision is made when a span STARTS, before its
 * outcome (success/error) is known, so "always sample errors" cannot be
 * expressed as a head-sampling decision. This class instead wraps the
 * real (OTLP) exporter and decides per finished span, when its status
 * IS known.
 * <p>
 * This only works because {@link TracingAutoConfiguration} pairs it with
 * an {@code AlwaysOnSampler}: every span must actually be recorded by
 * the SDK for this filter to have anything to inspect at export time.
 * The tradeoff is accepted deliberately (recording overhead for spans
 * that end up dropped here) rather than standing up a separate
 * OpenTelemetry Collector with tail-sampling, which is the "correct"
 * production answer but real additional infrastructure this project
 * doesn't otherwise need.
 */
public class ErrorAlwaysSampledSpanExporter implements SpanExporter {

    private final SpanExporter delegate;
    private final double sampleRate;

    public ErrorAlwaysSampledSpanExporter(SpanExporter delegate, double sampleRate) {
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
        return ThreadLocalRandom.current().nextDouble() < sampleRate;
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
