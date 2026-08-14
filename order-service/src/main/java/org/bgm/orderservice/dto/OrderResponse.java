package org.bgm.orderservice.dto;

import org.bgm.orderservice.model.Order;
import org.bgm.orderservice.model.OrderStatus;
import org.bgm.orderservice.model.PaymentStatus;

import java.time.Instant;
import java.util.List;

public record OrderResponse(
        long id,
        long customerId,
        Double totalAmount,
        Instant orderCreatedOn,
        OrderStatus orderStatus,
        PaymentStatus paymentStatus,
        List<Item> items
) {
    public record Item(long productId, int quantity, Double unitPrice) {
    }

    public static OrderResponse from(Order order) {
        List<Item> items = order.getItems().stream()
                .map(i -> new Item(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                .toList();
        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getTotalAmount(),
                order.getOrderCreatedOn(),
                order.getOrderStatus(),
                order.getPaymentStatus(),
                items
        );
    }
}
