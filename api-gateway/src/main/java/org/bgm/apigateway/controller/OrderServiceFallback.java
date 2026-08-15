package org.bgm.apigateway.controller;


import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    @RequestMapping("/fallback/order")
    public ResponseEntity<String> orderFallback() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Order service unavailable");
    }
}
