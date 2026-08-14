package org.bgm.paymentservice.dto;

import org.bgm.paymentservice.model.Payment;
import org.bgm.paymentservice.model.PaymentMethod;
import org.bgm.paymentservice.model.PaymentStatus;

public record PaymentResponse(
        long id,
        long orderId,
        Double amount,
        PaymentMethod method,
        String gateway,
        PaymentStatus status,
        String transactionId,
        String refundTransactionId,
        String refundReason
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getAmount(),
                payment.getMethod(),
                payment.getGateway(),
                payment.getStatus(),
                payment.getTransactionId(),
                payment.getRefundTransactionId(),
                payment.getRefundReason()
        );
    }
}
