package org.bgm.notificationservice.dispatch;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.bgm.common.correlation.CorrelationConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression case for the RabbitMQ-hop MDC gap (see
 * NotificationEventConsumerTest's Javadoc for the full root cause). Locks
 * down the consumer side: {@code handle()} must restore correlationId/
 * orderId/appTraceId into MDC from the RabbitMQ message headers
 * NotificationEventConsumer now sets, so its own log line is no longer
 * permanently blank on those fields. Logback's ListAppender captures each
 * ILoggingEvent's own MDC property-map snapshot — the only reliable way
 * to assert "MDC held this value at the moment this specific line
 * logged," independent of whatever pattern layout is configured.
 */
class NotificationDispatchWorkerTest {

    private Logger workerLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        workerLogger = (Logger) LoggerFactory.getLogger(NotificationDispatchWorker.class);
        appender = new ListAppender<>();
        appender.start();
        workerLogger.addAppender(appender);
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        workerLogger.detachAppender(appender);
        MDC.clear();
    }

    @Test
    void restoresCorrelationContextFromHeadersBeforeLogging() {
        NotificationDispatchWorker worker = new NotificationDispatchWorker();
        NotificationDispatchMessage message =
                new NotificationDispatchMessage(66, NotificationDispatchMessage.TYPE_ORDER_CONFIRMATION, Instant.now().toString());

        worker.handle(message, "corr-123", "trace-abc", null);

        ILoggingEvent event = lastDispatchLogEvent();
        assertEquals("corr-123", event.getMDCPropertyMap().get(CorrelationConstants.MDC_CORRELATION_ID_KEY));
        assertEquals("66", event.getMDCPropertyMap().get(CorrelationConstants.MDC_ORDER_ID_KEY));
        assertEquals("trace-abc", event.getMDCPropertyMap().get(CorrelationConstants.MDC_TRACE_ID_KEY));
    }

    @Test
    void degradesGracefullyWhenNoHeadersWerePropagated() {
        NotificationDispatchWorker worker = new NotificationDispatchWorker();
        NotificationDispatchMessage message =
                new NotificationDispatchMessage(70, NotificationDispatchMessage.TYPE_ORDER_CONFIRMATION, Instant.now().toString());

        worker.handle(message, null, null, null);

        ILoggingEvent event = lastDispatchLogEvent();
        // orderId is always set (OrderCorrelationScope.forOrder's own
        // parameter, not a propagated header) — correlationId falls back
        // to the order ID itself, matching OrderCorrelationScope's
        // documented degrade-instead-of-blank behavior; traceId stays
        // genuinely unset, same as every other consumer in this saga.
        assertEquals("70", event.getMDCPropertyMap().get(CorrelationConstants.MDC_ORDER_ID_KEY));
        assertEquals("70", event.getMDCPropertyMap().get(CorrelationConstants.MDC_CORRELATION_ID_KEY));
        assertNull(event.getMDCPropertyMap().get(CorrelationConstants.MDC_TRACE_ID_KEY));
    }

    @Test
    void mdcIsClearedAfterHandlingEvenOnFailure() {
        NotificationDispatchWorker worker = new NotificationDispatchWorker();
        // Test hook (see class Javadoc): a negative orderId can never
        // occur from a real order, deliberately triggers the failure path.
        NotificationDispatchMessage message =
                new NotificationDispatchMessage(-1, NotificationDispatchMessage.TYPE_ORDER_CONFIRMATION, Instant.now().toString());

        assertThrows(IllegalStateException.class, () -> worker.handle(message, "corr-1", "trace-1", null));

        // OrderCorrelationScope is try-with-resources — must not leak
        // stale correlation values onto whatever this pooled thread
        // handles next, even when the listener itself throws.
        assertNull(MDC.get(CorrelationConstants.MDC_CORRELATION_ID_KEY));
        assertNull(MDC.get(CorrelationConstants.MDC_ORDER_ID_KEY));
        assertNull(MDC.get(CorrelationConstants.MDC_TRACE_ID_KEY));
    }

    private ILoggingEvent lastDispatchLogEvent() {
        return appender.list.get(appender.list.size() - 1);
    }
}
