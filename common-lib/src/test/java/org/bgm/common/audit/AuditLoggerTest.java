package org.bgm.common.audit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-08-16 architecture review: AuditLogger's sensitive-key redaction
 * guard (the fix for ADR-0037's Confidentiality gap) shipped with no
 * test coverage. This locks the behavior down so a future change can't
 * silently regress it.
 */
class AuditLoggerTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger auditLogger;

    @BeforeEach
    void setUp() {
        auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(appender);
    }

    @Test
    void redactsFieldsWithSensitiveKeyNames() {
        AuditLogger.log("LOGIN", AuditLogger.fields()
                .with("email", "someone@example.com")
                .with("password", "hunter2")
                .with("orderId", 42)
                .build());

        String line = onlyLoggedLine();
        assertTrue(line.contains("email=[REDACTED]"), "email should be redacted: " + line);
        assertTrue(line.contains("password=[REDACTED]"), "password should be redacted: " + line);
        assertTrue(line.contains("orderId=42"), "non-sensitive fields should pass through: " + line);
        assertFalse(line.contains("someone@example.com"), "raw email must never reach the log line");
        assertFalse(line.contains("hunter2"), "raw password must never reach the log line");
    }

    @Test
    void redactionIsCaseInsensitiveAndMatchesSubstrings() {
        // "cardNumber" and "Email" — neither is an exact match for the
        // denylist entries ("card", "email") but both must still be
        // caught, since AuditLogger.isSensitiveKey checks by substring,
        // not exact match (see its Javadoc).
        AuditLogger.log("PAYMENT_ATTEMPT", AuditLogger.fields()
                .with("cardNumber", "4111111111111111")
                .with("Email", "someone@example.com")
                .build());

        String line = onlyLoggedLine();
        assertTrue(line.contains("cardNumber=[REDACTED]"), line);
        assertTrue(line.contains("Email=[REDACTED]"), line);
    }

    @Test
    void ordinaryFieldsAreLoggedVerbatim() {
        AuditLogger.log("ORDER_CREATED", AuditLogger.fields()
                .with("orderId", 7)
                .with("customerId", 3)
                .with("totalAmount", "6.33")
                .build());

        String line = onlyLoggedLine();
        assertEquals("auditEvent=ORDER_CREATED orderId=7 customerId=3 totalAmount=6.33", line);
    }

    private String onlyLoggedLine() {
        List<ILoggingEvent> events = appender.list;
        assertEquals(1, events.size(), "expected exactly one audit log line");
        return events.get(0).getFormattedMessage();
    }
}
