package org.bgm.orderservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.bgm.common.idempotency.IdempotencyKeyResolver;
import org.bgm.orderservice.dto.CreateOrderRequest;
import org.bgm.orderservice.dto.OrderResponse;
import org.bgm.orderservice.dto.PageResponse;
import org.bgm.orderservice.exception.InvalidOrderActionException;
import org.bgm.orderservice.model.OrderAction;
import org.bgm.orderservice.service.IdempotencyService;
import org.bgm.orderservice.service.OrderService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final IdempotencyService idempotencyService;

    // ADR-0024 (doc/adr/ADR-0024-idempotency-hybrid-key-hash.md): a repeat
    // call with the same resolved idempotency key (client-supplied header,
    // or a hash of this exact request body if the header is absent)
    // replays the first call's response instead of creating a second order.
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader(value = IdempotencyKeyResolver.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        return idempotencyService.execute(idempotencyKey, request, OrderResponse.class, () -> {
            var order = orderService.createOrder(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(order));
        });
    }

    @GetMapping("{id}")
    public OrderResponse getOrder(@PathVariable("id") long id) {
        return OrderResponse.from(orderService.getOrder(id));
    }

    @PutMapping("{id}/{action}")
    public OrderResponse updateStatus(@PathVariable("id") long orderId, @PathVariable("action") String action) {
        return OrderResponse.from(orderService.updateStatus(orderId, parseAction(action)));
    }

    private static final int MAX_PAGE_SIZE = 100;

    @GetMapping("customer/{customerId}")
    public PageResponse<OrderResponse> getOrdersForCustomer(
            @PathVariable("customerId") long customerId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        var pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "id"));
        return PageResponse.from(orderService.getOrdersForCustomer(customerId, pageable).map(OrderResponse::from));
    }

    private OrderAction parseAction(String action) {
        try {
            return OrderAction.valueOf(action.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidOrderActionException("Unknown order action: '" + action + "' (expected cancel or return)");
        }
    }
}
