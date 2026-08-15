package org.bgm.catalogservice.exception;

import org.bgm.common.audit.AuditLogger;
import org.bgm.common.correlation.CorrelationConstants;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ProductNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(NotProductOwnerException.class)
    public ResponseEntity<Map<String, Object>> handleNotOwner(NotProductOwnerException ex) {
        // A PROVIDER touching another provider's product — same class of
        // security-relevant denial as a role-gate 403 (see common-lib's
        // AccessDeniedAuditAdvice), just enforced as a business rule
        // instead of a Spring Security annotation, so it needs its own
        // audit call rather than being caught by that shared advice.
        AuditLogger.log("ACCESS_DENIED", AuditLogger.fields()
                .with("correlationId", MDC.get(CorrelationConstants.MDC_CORRELATION_ID_KEY))
                .with("reason", "not_product_owner")
                .with("detail", ex.getMessage())
                .build());
        return problem(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseEntity<Map<String, Object>> problem(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
