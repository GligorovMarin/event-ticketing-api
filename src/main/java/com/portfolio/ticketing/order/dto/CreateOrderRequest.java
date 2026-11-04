package com.portfolio.ticketing.order.dto;

import com.portfolio.ticketing.order.OrderRules;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;
import java.util.UUID;

/**
 * Body of {@code POST /orders}: "I want these seats of this event".
 *
 * <p>The client sends seat IDS (taken from the seat map, GET /events/{id}/seats). It does NOT send prices or a
 * total: the server looks the prices up itself, so a client cannot pay less by editing a number.
 *
 * @param seatIds between 1 and {@link OrderRules#MAX_SEATS_PER_ORDER} different seats
 */
public record CreateOrderRequest(
        @NotNull UUID eventId,
        @NotEmpty @Size(max = OrderRules.MAX_SEATS_PER_ORDER) @UniqueElements List<@NotNull UUID> seatIds) {
}
