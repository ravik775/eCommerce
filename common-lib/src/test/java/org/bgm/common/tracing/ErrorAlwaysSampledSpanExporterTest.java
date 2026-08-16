package org.bgm.common.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-08-16 architecture review: two real bugs were fixed in
 * {@link ErrorAlwaysSampledSpanExporter} this session with no test
 * coverage — (1) the 4xx-status check only looked at a {@code long}-typed
 * attribute, silently missing Micrometer's actual string-typed
 * {@code http.response.status_code}; (2) {@code force_trace} tagging
 * depended on filter-ordering bugs elsewhere that made the attribute
 * never actually land on a span. This locks the exporter's own decision
 * logic down directly, independent of those upstream ordering concerns.
 */
class ErrorAlwaysSampledSpanExporterTest {

    private static final AttributeKey<String> HTTP_STATUS_STR = AttributeKey.stringKey("http.response.status_code");
    private static final AttributeKey<Long> HTTP_STATUS_LONG = AttributeKey.longKey("http.response.status_code");

    @Test
    void exportsSpanWhenStringTypedStatusIs4xx() {
        RecordingExporter delegate = new RecordingExporter();
        ErrorAlwaysSampledSpanExporter exporter = new ErrorAlwaysSampledSpanExporter(delegate, 0.0);

        SpanData span = span(StatusData.unset(), Attributes.of(HTTP_STATUS_STR, "404"));
        exporter.export(List.of(span));

        assertEquals(1, delegate.exported.size(), "a string-typed 4xx status must be force-exported");
    }

    @Test
    void exportsSpanWhenLongTypedStatusIs4xx() {
        RecordingExporter delegate = new RecordingExporter();
        ErrorAlwaysSampledSpanExporter exporter = new ErrorAlwaysSampledSpanExporter(delegate, 0.0);

        SpanData span = span(StatusData.unset(), Attributes.of(HTTP_STATUS_LONG, 500L));
        exporter.export(List.of(span));

        assertEquals(1, delegate.exported.size(), "a long-typed 5xx status must be force-exported");
    }

    @Test
    void doesNotExport2xxAtZeroSampleRate() {
        RecordingExporter delegate = new RecordingExporter();
        ErrorAlwaysSampledSpanExporter exporter = new ErrorAlwaysSampledSpanExporter(delegate, 0.0);

        SpanData span = span(StatusData.unset(), Attributes.of(HTTP_STATUS_STR, "200"));
        exporter.export(List.of(span));

        assertTrue(delegate.exported.isEmpty(), "a healthy 2xx span at 0% sample rate must not be exported");
    }

    @Test
    void exportsSpanWithForceTraceAttributeRegardlessOfStatus() {
        RecordingExporter delegate = new RecordingExporter();
        ErrorAlwaysSampledSpanExporter exporter = new ErrorAlwaysSampledSpanExporter(delegate, 0.0);

        SpanData span = span(StatusData.unset(),
                Attributes.of(ErrorAlwaysSampledSpanExporter.FORCE_TRACE_ATTRIBUTE, true, HTTP_STATUS_STR, "200"));
        exporter.export(List.of(span));

        assertEquals(1, delegate.exported.size(), "force_trace=true must be force-exported even on a healthy status");
    }

    @Test
    void exportsSpanWithOtelErrorStatusRegardlessOfHttpAttribute() {
        RecordingExporter delegate = new RecordingExporter();
        ErrorAlwaysSampledSpanExporter exporter = new ErrorAlwaysSampledSpanExporter(delegate, 0.0);

        SpanData span = span(StatusData.error(), Attributes.empty());
        exporter.export(List.of(span));

        assertEquals(1, delegate.exported.size(), "OTel-level ERROR status must always be exported");
    }

    private static SpanData span(StatusData status, Attributes attributes) {
        return TestSpanData.builder()
                .setName("http get /test")
                .setKind(SpanKind.SERVER)
                .setSpanContext(SpanContext.create(
                        "0af7651916cd43dd8448eb211c80319c",
                        "b7ad6b7169203331",
                        TraceFlags.getSampled(),
                        TraceState.getDefault()))
                .setStatus(status)
                .setStartEpochNanos(0)
                .setEndEpochNanos(1_000_000)
                .setHasEnded(true)
                .setAttributes(attributes)
                .setResource(Resource.getDefault())
                .setInstrumentationScopeInfo(InstrumentationScopeInfo.create("test"))
                .build();
    }

    private static final class RecordingExporter implements SpanExporter {
        final List<SpanData> exported = new ArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            exported.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
