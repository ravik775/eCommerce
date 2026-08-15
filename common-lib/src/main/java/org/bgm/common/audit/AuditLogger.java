package org.bgm.common.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 7 (SOC2 alignment): a queryable audit trail for a login→order→
 * payment sequence, without standing up a dedicated audit store — every
 * emitting service already ships stdout logs to the same place
 * (`kubectl logs`), which is the same "queryable" bar the rest of this
 * environment's live verification has used throughout (correlation IDs
 * grepped out of pod logs, not a log-aggregation stack, since none
 * exists here — see ADR-0023).
 * <p>
 * Not keyed by the request-scoped correlation ID directly (ADR-0023's
 * {@code CorrelationConstants}) — the LOGIN event has no order yet, so
 * it can only join to what follows via {@code customerId}. Once an
 * order exists, every subsequent step (ORDER_CREATED onward) DOES share
 * one correlation ID: {@link org.bgm.common.correlation.OrderCorrelationScope}
 * sets it to the order ID itself across the Kafka-driven saga
 * (order-service, inventory-service, payment-service, notification-service),
 * so those events ARE already joinable as one correlationId in the logs
 * even though this logger doesn't additionally stamp it on the audit
 * line — the surrounding log line (same MDC) carries it.
 * <p>
 * One INFO line per event on a dedicated "AUDIT" logger name (not the
 * class's own logger) so a deployment can route/filter/sample it
 * independently of ordinary application logs via standard logging
 * config, without any code change here.
 */
public final class AuditLogger {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    private AuditLogger() {
    }

    /**
     * @param event  short, stable event name (e.g. "LOGIN", "ORDER_CREATED", "PAYMENT_SUCCESS")
     * @param fields ordered key-value pairs describing the event; values are logged via
     *               {@code String.valueOf}, so pass already-formatted/safe values
     */
    public static void log(String event, Map<String, ?> fields) {
        StringBuilder line = new StringBuilder("auditEvent=").append(event);
        fields.forEach((key, value) -> line.append(' ').append(key).append('=').append(value));
        AUDIT.info(line.toString());
    }

    /** Convenience builder for the common case of a handful of fields, in call order. */
    public static Fields fields() {
        return new Fields();
    }

    public static final class Fields {
        private final Map<String, Object> values = new LinkedHashMap<>();

        public Fields with(String key, Object value) {
            values.put(key, value);
            return this;
        }

        public Map<String, Object> build() {
            return values;
        }
    }
}
