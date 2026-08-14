package org.bgm.orderservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record CreateOrderRequest(
        @NotNull Long customerId,
        @NotEmpty @Valid List<Item> items
) {
    public record Item(
            @NotNull Long productId,
            @Positive int quantity,
            @NotNull @Positive Double unitPrice
    ) {
    }
}
