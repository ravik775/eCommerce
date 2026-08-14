package org.bgm.paymentservice.exception;

public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(long paymentId) {
        super("Payment not found: " + paymentId);
    }
}
