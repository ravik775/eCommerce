package org.bgm.paymentservice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.bgm.paymentservice.model.PaymentMethod;

public record InitiatePaymentRequest(
        @NotNull Long orderId,
        @NotNull @Positive Double amount,
        @NotNull PaymentMethod method
) {
}
