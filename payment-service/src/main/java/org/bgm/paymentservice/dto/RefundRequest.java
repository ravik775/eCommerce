package org.bgm.paymentservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// Notes.md specifies a flat `POST /payments/refund` endpoint (not
// `/payments/{id}/refund`), so paymentId travels in the request body.
public record RefundRequest(@NotNull Long paymentId, @NotBlank String reason) {
}
