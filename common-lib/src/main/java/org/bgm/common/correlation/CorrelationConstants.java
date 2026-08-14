package org.bgm.common.correlation;

/**
 * ADR-0023 (doc/adr/ADR-0023-correlation-trace-id.md): correlation ID and
 * trace ID are distinct headers with distinct uniqueness guarantees — see
 * the ADR for why they are not the same value.
 */
public final class CorrelationConstants {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    public static final String MDC_CORRELATION_ID_KEY = "correlationId";
    public static final String MDC_TRACE_ID_KEY = "traceId";

    private CorrelationConstants() {
    }
}
