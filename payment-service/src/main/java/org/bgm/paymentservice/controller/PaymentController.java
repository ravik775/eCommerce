package org.bgm.paymentservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.bgm.common.idempotency.IdempotencyKeyResolver;
import org.bgm.paymentservice.dto.InitiatePaymentRequest;
import org.bgm.paymentservice.dto.PaymentResponse;
import org.bgm.paymentservice.dto.RefundRequest;
import org.bgm.paymentservice.service.IdempotencyService;
import org.bgm.paymentservice.service.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final IdempotencyService idempotencyService;

    // ADR-0024: same hybrid key+hash idempotency as order-service's
    // POST /orders — a payment retry must not double-charge.
    @PostMapping
    public ResponseEntity<PaymentResponse> initiate(
            @Valid @RequestBody InitiatePaymentRequest request,
            @RequestHeader(value = IdempotencyKeyResolver.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        return idempotencyService.execute(idempotencyKey, request, PaymentResponse.class, () -> {
            var payment = paymentService.initiate(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(PaymentResponse.from(payment));
        });
    }

    @PostMapping("/refund")
    public PaymentResponse refund(@Valid @RequestBody RefundRequest request) {
        return PaymentResponse.from(paymentService.refund(request.paymentId(), request.reason()));
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse getPayment(@PathVariable("paymentId") long paymentId) {
        return PaymentResponse.from(paymentService.getPayment(paymentId));
    }
}
