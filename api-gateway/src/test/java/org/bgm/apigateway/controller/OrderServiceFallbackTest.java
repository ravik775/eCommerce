package org.bgm.apigateway.controller;

import org.bgm.common.correlation.CorrelationConstants;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 2026-08-16: locks down the fix for a real bug reported live — a
 * checkout failure during a circuit-breaker trip showed
 * "Checkout failed: POST /order -> 503 (ref: unknown)" in the UI. Root
 * cause: this fallback's plain {@code ResponseEntity} controls its own
 * header map from scratch, which silently drops whatever
 * CorrelationTraceGatewayFilter had already set on the exchange's
 * response before the CircuitBreaker filter's internal forward. Fixed
 * by reading the correlation/trace IDs back off the *request* (they
 * survive the forward as request headers) and re-adding them here.
 */
class OrderServiceFallbackTest {

    private final OrderServiceFallback fallback = new OrderServiceFallback();

    @Test
    void echoesCorrelationAndTraceIdOntoTheFallbackResponse() {
        ResponseEntity<String> response = fallback.orderFallback("abc-123", "trace-456");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("abc-123", response.getHeaders().getFirst(CorrelationConstants.CORRELATION_ID_HEADER));
        assertEquals("trace-456", response.getHeaders().getFirst(CorrelationConstants.TRACE_ID_HEADER));
    }

    @Test
    void doesNotBlowUpWhenHeadersAreAbsent() {
        ResponseEntity<String> response = fallback.orderFallback(null, null);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNull(response.getHeaders().getFirst(CorrelationConstants.CORRELATION_ID_HEADER));
        assertNull(response.getHeaders().getFirst(CorrelationConstants.TRACE_ID_HEADER));
    }
}
