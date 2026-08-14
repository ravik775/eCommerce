package org.bgm.orderservice.service;

import lombok.RequiredArgsConstructor;
import org.bgm.common.event.schema.EventType;
import org.bgm.orderservice.dto.CreateOrderRequest;
import org.bgm.orderservice.event.OrderCancelledEvent;
import org.bgm.orderservice.event.OrderCreatedEvent;
import org.bgm.orderservice.event.OrderReturnedEvent;
import org.bgm.orderservice.exception.InvalidOrderActionException;
import org.bgm.orderservice.exception.OrderNotFoundException;
import org.bgm.orderservice.model.Order;
import org.bgm.orderservice.model.OrderAction;
import org.bgm.orderservice.model.OrderItem;
import org.bgm.orderservice.model.OrderStatus;
import org.bgm.orderservice.model.PaymentStatus;
import org.bgm.orderservice.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderService {

    private static final Set<OrderStatus> TERMINAL_OR_IN_TRANSIT = Set.of(
            OrderStatus.SHIPPED, OrderStatus.DELIVERED, OrderStatus.CANCELLED, OrderStatus.RETURNED
    );

    private final OrderRepository orderRepository;
    private final OrderEventPublisher eventPublisher;

    @Transactional
    public Order createOrder(CreateOrderRequest request) {
        Order order = new Order();
        order.setCustomerId(request.customerId());
        order.setOrderCreatedOn(Instant.now());
        order.setOrderStatus(OrderStatus.CREATED);
        order.setPaymentStatus(PaymentStatus.PAYMENT_NOT_INITIATED);

        double total = 0.0;
        for (CreateOrderRequest.Item itemReq : request.items()) {
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProductId(itemReq.productId());
            item.setQuantity(itemReq.quantity());
            item.setUnitPrice(itemReq.unitPrice());
            order.getItems().add(item);
            total += itemReq.quantity() * itemReq.unitPrice();
        }
        order.setTotalAmount(total);

        order = orderRepository.save(order); // IDENTITY strategy: id populated immediately

        // ADR-0007: outbox row written in this same transaction as the
        // order row above — atomic, closes the dual-write problem.
        List<OrderCreatedEvent.Item> eventItems = order.getItems().stream()
                .map(i -> new OrderCreatedEvent.Item(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                .toList();
        OrderCreatedEvent event = new OrderCreatedEvent(
                UUID.randomUUID().toString(),
                order.getId(),
                String.valueOf(order.getCustomerId()),
                eventItems,
                order.getTotalAmount(),
                Instant.now().toString()
        );
        eventPublisher.publish(EventType.ORDER_CREATED, order.getId(), event.eventId(), event);

        return order;
    }

    @Transactional(readOnly = true)
    public Order getOrder(long id) {
        return orderRepository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Order> getOrdersForCustomer(long customerId) {
        return orderRepository.findByCustomerId(customerId);
    }

    @Transactional
    public Order updateStatus(long id, OrderAction action) {
        Order order = getOrder(id);
        switch (action) {
            case CANCEL -> cancel(order);
            case RETURN -> markReturned(order);
        }
        return orderRepository.save(order);
    }

    /** Called by the saga consumer (OrderSagaConsumer) — no HTTP action for this transition. */
    @Transactional
    public void applyPaymentOutcome(long orderId, boolean success) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        order.setPaymentStatus(success ? PaymentStatus.PAYMENT_SUCCESS : PaymentStatus.PAYMENT_FAILED);
        order.setOrderStatus(success ? OrderStatus.PROCESSING : OrderStatus.CANCELLED);
        orderRepository.save(order);
    }

    /** Called by the saga consumer when inventory-service can't reserve stock. */
    @Transactional
    public void applyInventoryReservationFailure(long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        order.setOrderStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
    }

    private void cancel(Order order) {
        if (TERMINAL_OR_IN_TRANSIT.contains(order.getOrderStatus())) {
            throw new InvalidOrderActionException(
                    "Order " + order.getId() + " cannot be cancelled from status " + order.getOrderStatus());
        }
        order.setOrderStatus(OrderStatus.CANCELLED);

        OrderCancelledEvent event = new OrderCancelledEvent(
                UUID.randomUUID().toString(), order.getId(), "customer request", Instant.now().toString());
        eventPublisher.publish(EventType.ORDER_CANCELLED, order.getId(), event.eventId(), event);
    }

    private void markReturned(Order order) {
        if (order.getOrderStatus() != OrderStatus.DELIVERED) {
            throw new InvalidOrderActionException(
                    "Order " + order.getId() + " cannot be returned from status " + order.getOrderStatus());
        }
        order.setOrderStatus(OrderStatus.RETURNED);

        List<OrderReturnedEvent.Item> items = order.getItems().stream()
                .map(i -> new OrderReturnedEvent.Item(i.getProductId(), i.getQuantity()))
                .toList();
        OrderReturnedEvent event = new OrderReturnedEvent(
                UUID.randomUUID().toString(), order.getId(), items, Instant.now().toString());
        eventPublisher.publish(EventType.ORDER_RETURNED, order.getId(), event.eventId(), event);
    }
}
