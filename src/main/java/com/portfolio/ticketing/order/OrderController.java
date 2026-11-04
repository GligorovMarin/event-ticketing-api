package com.portfolio.ticketing.order;

import com.portfolio.ticketing.order.dto.CreateOrderRequest;
import com.portfolio.ticketing.order.dto.OrderResponse;
import com.portfolio.ticketing.order.dto.PayOrderRequest;
import com.portfolio.ticketing.ratelimit.RateLimited;
import com.portfolio.ticketing.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * HTTP layer for a customer's orders. Customers only; organizers have no orders of their own.
 *
 * <pre>
 *   POST /orders        hold seats for 10 minutes (rate limited: 10 attempts per minute per customer)
 *   GET  /orders        my orders, newest first
 *   GET  /orders/{id}   one of my orders
 *   POST /orders/{id}/pay      pay for a pending order (rate limited: 5 attempts per minute)
 *   POST /orders/{id}/cancel   cancel it; a paid order is refunded, until 48 hours before the event
 * </pre>
 */
@RestController
@RequestMapping("/orders")
@PreAuthorize("hasRole('CUSTOMER')")
@Tag(name = "orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderLifecycleService lifecycleService;

    public OrderController(OrderService orderService, OrderLifecycleService lifecycleService) {
        this.orderService = orderService;
        this.lifecycleService = lifecycleService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimited(limit = OrderRules.ORDER_ATTEMPTS_PER_MINUTE, windowSeconds = 60)
    @Operation(summary = "Hold seats for 10 minutes",
            description = "Send the seat ids from the seat map. 409 if any seat is taken (nothing is held then), "
                    + "or if you already have an order in progress for this event. Max 6 seats per order.")
    public OrderResponse create(AuthenticatedUser customer, @Valid @RequestBody CreateOrderRequest request) {
        return orderService.create(customer, request);
    }

    @GetMapping
    @Operation(summary = "My orders, newest first")
    public List<OrderResponse> mine(AuthenticatedUser customer) {
        return orderService.listMine(customer);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One of my orders")
    public OrderResponse get(AuthenticatedUser customer, @PathVariable UUID id) {
        return orderService.getMine(customer, id);
    }

    @PostMapping("/{id}/pay")
    // Paying CHANGES an existing order rather than creating one, so 200 OK (not 201).
    @RateLimited(limit = OrderRules.PAYMENT_ATTEMPTS_PER_MINUTE, windowSeconds = 60)
    @Operation(summary = "Pay for a pending order",
            description = "Send a payment token: tok_ok (approved), tok_declined (402, try again with another), "
                    + "tok_timeout (503, nothing charged). Paying an already paid order returns it without charging again.")
    public OrderResponse pay(AuthenticatedUser customer, @PathVariable UUID id, @Valid @RequestBody PayOrderRequest request) {
        return lifecycleService.pay(customer, id, request.paymentToken());
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an order",
            description = "A pending order can be cancelled any time. A paid order only until 48 hours before the event, "
                    + "and it is refunded. The seats go back on sale.")
    public OrderResponse cancel(AuthenticatedUser customer, @PathVariable UUID id) {
        return lifecycleService.cancel(customer, id);
    }
}
