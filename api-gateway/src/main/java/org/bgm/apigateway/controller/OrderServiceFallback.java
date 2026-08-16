package org.bgm.apigateway.controller;


import org.bgm.common.correlation.CorrelationConstants;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderServiceFallback {
    // No method restriction: the CircuitBreaker filter forwards to this
    // URI with the original request's method preserved (forward: keeps
    // it), so a POST /order that trips the breaker forwards internally
    // as POST /fallback/order — a @GetMapping-only handler here 405'd
    // that forward (found live: WebFlux's default error path reports the
    // pre-forward /order path, which is why the 405 looked like a
    // routing bug rather than a fallback-handler method mismatch).
    //
    // Found live (2026-08-16): the UI's "ref: unknown" on a 503 checkout
    // failure — CorrelationTraceGatewayFilter sets these headers on the
    // exchange's response before the CircuitBreaker filter ever
    // evaluates, but a plain @RestController-returned ResponseEntity
    // controls its own header map from scratch during the internal
    // forward's response commit, wiping whatever the exchange already
    // had rather than merging with it. Reading them back off the
    // *request* here (they survive the forward as request headers,
    // set by the same filter) and re-adding them to this response is
    // the fix — the same correlation ID a customer/support agent
    // would otherwise be unable to trace this specific failed request
    // by.
    @RequestMapping("/fallback/order")
    public ResponseEntity<String> orderFallback(
            @RequestHeader(value = CorrelationConstants.CORRELATION_ID_HEADER, required = false) String correlationId,
            @RequestHeader(value = CorrelationConstants.TRACE_ID_HEADER, required = false) String traceId) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE);
        if (correlationId != null) {
            builder.header(CorrelationConstants.CORRELATION_ID_HEADER, correlationId);
        }
        if (traceId != null) {
            builder.header(CorrelationConstants.TRACE_ID_HEADER, traceId);
        }
        return builder.body("Order service unavailable");
    }
}
