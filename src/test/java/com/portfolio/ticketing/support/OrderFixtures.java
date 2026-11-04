package com.portfolio.ticketing.support;

import com.portfolio.ticketing.order.OrderLifecycleService;
import com.portfolio.ticketing.order.OrderService;
import com.portfolio.ticketing.order.dto.CreateOrderRequest;
import com.portfolio.ticketing.order.dto.OrderResponse;
import com.portfolio.ticketing.security.AuthenticatedUser;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Creates and pays orders for tests through the REAL service code, skipping HTTP. A test about cancellation should not
 * repeat the ceremony of "create an order, then pay it" in every method.
 */
@Component
@Profile("test")
public class OrderFixtures {

    private final OrderService orderService;
    private final OrderLifecycleService lifecycle;

    public OrderFixtures(OrderService orderService, OrderLifecycleService lifecycle) {
        this.orderService = orderService;
        this.lifecycle = lifecycle;
    }

    private static AuthenticatedUser asUser(TestAccounts.Account account) {
        return new AuthenticatedUser(account.id(), account.role());
    }

    /** A PENDING order: the seats are held. */
    public OrderResponse place(TestAccounts.Account customer, UUID eventId, UUID... seatIds) {
        return orderService.create(asUser(customer), new CreateOrderRequest(eventId, List.of(seatIds)));
    }

    /** A PAID order: placed, then paid with the always-approved token. */
    public OrderResponse placeAndPay(TestAccounts.Account customer, UUID eventId, UUID... seatIds) {
        OrderResponse order = place(customer, eventId, seatIds);

        return lifecycle.pay(asUser(customer), order.id(), "tok_ok");
    }

    /** A PAID order whose payment can never be refunded (to test a failing refund). */
    public OrderResponse placeAndPayUnrefundable(TestAccounts.Account customer, UUID eventId, UUID... seatIds) {
        OrderResponse order = place(customer, eventId, seatIds);

        return lifecycle.pay(asUser(customer), order.id(), "tok_refund_fails");
    }
}
