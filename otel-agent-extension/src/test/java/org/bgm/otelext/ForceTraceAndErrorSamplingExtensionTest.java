package org.bgm.otelext;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0064: exercises ErrorAlwaysSampledDelegatingExporter directly
 * (mirrors common-lib's ErrorAlwaysSampledSpanExporterTest cases) rather
 * than going through AutoConfigurationCustomizer/AutoConfigurationCustomizerProvider
 * at all — an earlier version of this test hand-implemented that SPI
 * interface with an anonymous class and broke twice across two Docker
 * rebuilds on missing-abstract-method compile errors as the resolved
 * OTel SDK version picked up newly-added interface methods this class
 * never uses. ForceTraceAndErrorSamplingExtension.customize() itself
 * (the actual SPI registration call) is one line of framework glue,
 * deliberately left untested — the filtering policy below is the part
 * with real logic.
 */
class ForceTraceAndErrorSamplingExtensionTest {

    private SpanData spanWithStatus(StatusData status) {
        return TestSpanData.builder()
                .setName("test")
                .setStatus(status)
                .setKind(io.opentelemetry.api.trace.SpanKind.SERVER)
                .setStartEpochNanos(0)
                .setEndEpochNanos(1)
                .setHasEnded(true)
                .build();
    }

    private SpanData spanWithAttributes(Attributes attributes) {
        return TestSpanData.builder()
                .setName("test")
                .setStatus(StatusData.unset())
                .setAttributes(attributes)
                .setKind(io.opentelemetry.api.trace.SpanKind.SERVER)
                .setStartEpochNanos(0)
                .setEndEpochNanos(1)
                .setHasEnded(true)
                .build();
    }

    @Test
    void alwaysExportsErrorStatusSpans() {
        List<SpanData> exported = new ArrayList<>();
        SpanExporter filtered = new ForceTraceAndErrorSamplingExtension.ErrorAlwaysSampledDelegatingExporter(
                recordingExporter(exported), 0.0);

        filtered.export(List.of(spanWithStatus(StatusData.error())));

        assertEquals(1, exported.size());
    }

    @Test
    void alwaysExportsHttpErrorStatusCodeSpans() {
        List<SpanData> exported = new ArrayList<>();
        SpanExporter filtered = new ForceTraceAndErrorSamplingExtension.ErrorAlwaysSampledDelegatingExporter(
                recordingExporter(exported), 0.0);

        Attributes attrs = Attributes.of(AttributeKey.longKey("http.response.status_code"), 500L);
        filtered.export(List.of(spanWithAttributes(attrs)));

        assertEquals(1, exported.size());
    }

    @Test
    void alwaysExportsForceTraceAttributedSpans() {
        List<SpanData> exported = new ArrayList<>();
        SpanExporter filtered = new ForceTraceAndErrorSamplingExtension.ErrorAlwaysSampledDelegatingExporter(
                recordingExporter(exported), 0.0);

        Attributes attrs = Attributes.of(AttributeKey.booleanKey("force_trace"), true);
        filtered.export(List.of(spanWithAttributes(attrs)));

        assertEquals(1, exported.size());
    }

    @Test
    void dropsSuccessfulUnforcedSpansAtZeroSampleRate() {
        List<SpanData> exported = new ArrayList<>();
        SpanExporter filtered = new ForceTraceAndErrorSamplingExtension.ErrorAlwaysSampledDelegatingExporter(
                recordingExporter(exported), 0.0);

        filtered.export(List.of(spanWithAttributes(Attributes.empty())));

        assertTrue(exported.isEmpty());
    }

    @Test
    void exportsSuccessfulUnforcedSpansAtFullSampleRate() {
        List<SpanData> exported = new ArrayList<>();
        SpanExporter filtered = new ForceTraceAndErrorSamplingExtension.ErrorAlwaysSampledDelegatingExporter(
                recordingExporter(exported), 1.0);

        filtered.export(List.of(spanWithAttributes(Attributes.empty())));

        assertEquals(1, exported.size());
    }

    private SpanExporter recordingExporter(List<SpanData> sink) {
        return new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                sink.addAll(spans);
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
        };
    }
}
