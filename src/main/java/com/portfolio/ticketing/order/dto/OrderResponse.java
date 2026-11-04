package com.portfolio.ticketing.order.dto;

import com.portfolio.ticketing.order.OrderStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An order as the customer sees it.
 *
 * <p>{@code status} is the EFFECTIVE status (see order/Order#effectiveStatus): a pending order whose hold has run
 * out is shown as EXPIRED. {@code holdExpiresAt} is when the seats stop being reserved while the order is PENDING.
 */
public record OrderResponse(
        UUID id,
        UUID eventId,
        String eventName,
        OrderStatus status,
        int totalCents,
        Instant holdExpiresAt,
        Instant createdAt,
        Instant paidAt,
        Instant cancelledAt,
        List<Item> items) {

    /** One seat of the order: where it is, and the price it cost (copied at order time). */
    public record Item(UUID seatId, String section, String row, int number, String label, int priceCents) {
    }
}
