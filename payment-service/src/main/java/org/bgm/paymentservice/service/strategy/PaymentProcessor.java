package org.bgm.paymentservice.service.strategy;

import org.bgm.paymentservice.model.PaymentMethod;

/**
 * Strategy pattern per Notes.md's "Payment Service" section. Each
 * implementation is a simulated/mock gateway (no real Stripe/Razorpay/
 * Paytm/PhonePe integration in this phase — Notes.md's "Gateway Adapters"
 * are a documented future integration point, not built here since they'd
 * require real sandbox credentials this project doesn't have).
 */
public interface PaymentProcessor {
    PaymentMethod method();

    ProcessorResult pay(long orderId, double amount);

    record ProcessorResult(boolean success, String gatewayName, String transactionId, String failureReason) {
    }
}
