package org.bgm.userservice.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-08-16 architecture review: this exception's message used to
 * embed the raw email address (see git history), which reached the
 * HTTP 409 response body verbatim via GlobalExceptionHandler — both a
 * PII leak and an account-enumeration oracle. Locks down that the
 * message stays generic regardless of future changes.
 */
class DuplicateEmailExceptionTest {

    @Test
    void messageDoesNotContainAnyEmailAddress() {
        String message = new DuplicateEmailException().getMessage();

        assertTrue(message.toLowerCase().contains("email"), "message should still explain the conflict: " + message);
        assertFalse(message.contains("@"), "message must never contain an actual email address: " + message);
    }
}
