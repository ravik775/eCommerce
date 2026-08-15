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
 * Deliberately NOT keyed by the request-scoped correlation ID
 * (ADR-0023's {@code CorrelationConstants}): that ID doesn't survive a
 * Kafka hop (no header propagation into event payloads), so a login →
 * order-created → payment-outcome sequence can never share one. The
 * actual join keys across this trail are {@code customerId} (login →
 * order) and {@code orderId} (order → payment) — both already present
 * on every event in this saga (ADR-0007), so no new correlation
 * mechanism is needed.
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
