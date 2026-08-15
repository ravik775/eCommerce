package org.bgm.common.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.bgm.common.correlation.CorrelationConstants;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Logs every {@code @PreAuthorize}/authorization denial (ADR-0025) on the
 * dedicated AUDIT logger before returning the ordinary 403 — the security-
 * failure counterpart to the LOGIN/ORDER_CREATED/PAYMENT_* success events
 * {@link AuditLogger} already emits. Handles both
 * {@link AccessDeniedException} (older Spring Security call sites) and
 * {@link AuthorizationDeniedException} (the exception
 * {@code @PreAuthorize} itself throws as of Spring Security 6) so neither
 * denial path silently skips the audit trail.
 * <p>
 * {@code @Order(HIGHEST_PRECEDENCE)}: a service's own {@code @ExceptionHandler}
 * for the same exception type (if any) should still win — this advice only
 * needs to see the exception once to log it, not own the response, so it
 * stays out of the way of more specific handlers by being tried last would
 * be wrong; instead it declares HIGHEST_PRECEDENCE and always returns the
 * same plain 403 itself, since no service in this codebase customizes the
 * access-denied response body today.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccessDeniedAuditAdvice {

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<Void> handle(RuntimeException ex, HttpServletRequest request) {
        String principal = SecurityContextHolder.getContext().getAuthentication() != null
                ? String.valueOf(SecurityContextHolder.getContext().getAuthentication().getName())
                : "unknown";
        AuditLogger.log("ACCESS_DENIED", AuditLogger.fields()
                .with("correlationId", MDC.get(CorrelationConstants.MDC_CORRELATION_ID_KEY))
                .with("path", request.getRequestURI())
                .with("method", request.getMethod())
                .with("principal", principal)
                .build());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
}
