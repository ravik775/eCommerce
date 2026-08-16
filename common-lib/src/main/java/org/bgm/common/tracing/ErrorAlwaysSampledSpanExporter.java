package org.bgm.common.tracing;

import io.opentelemetry.api.common.AttributeKey;
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

    // ADR-0032: the CAN_TRACE-gated Settings toggle's force-trace flag,
    // stamped onto the request's own span by ForceTraceFilter (servlet
    // services) / CorrelationTraceGatewayFilter (the gateway) when
    // X-Force-Trace: true is present. Checked here, at export time, same
    // as the error-status check: both are "was this span already
    // decided to be interesting," just from a different source.
    public static final AttributeKey<Boolean> FORCE_TRACE_ATTRIBUTE = AttributeKey.booleanKey("force_trace");

    // Found live: Spring's default HTTP server instrumentation only sets
    // OTel span STATUS to ERROR for 5xx responses — a 403/404/400 stays
    // UNSET, so checking span.getStatus() alone silently missed the
    // actual requirement ("every failed request is logged"), which
    // means every 4xx too, not just 5xx. Checked directly against the
    // HTTP status code attribute instead, covering both semantic-
    // convention key generations (Spring Boot has changed this key name
    // across versions).
    //
    // Found live (second bug): Micrometer Observation tags are always
    // String-typed; the OTel bridge exports them as OTel *string*
    // attributes, not longs. AttributeKey is type-tagged, so a
    // longKey("http.response.status_code") lookup against a
    // stringKey-typed attribute of the same name silently returns null
    // — this is why 4xx spans weren't being force-exported even though
    // the attribute was genuinely present. Both typed keys are checked
    // for both names now, string first since that's what's actually on
    // the wire in practice.
    private static final AttributeKey<Long> HTTP_STATUS_CODE = AttributeKey.longKey("http.response.status_code");
    private static final AttributeKey<Long> HTTP_STATUS_CODE_LEGACY = AttributeKey.longKey("http.status_code");
    private static final AttributeKey<String> HTTP_STATUS_CODE_STR = AttributeKey.stringKey("http.response.status_code");
    private static final AttributeKey<String> HTTP_STATUS_CODE_LEGACY_STR = AttributeKey.stringKey("http.status_code");

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
