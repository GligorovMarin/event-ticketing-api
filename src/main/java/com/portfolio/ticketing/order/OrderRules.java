package com.portfolio.ticketing.order;

import java.time.Duration;

/**
 * The business rules of buying tickets, as named constants (agreed in the project's planning phase).
 * Used by availability/ (how many seats a "find seats together" search may ask for) and, from the next
 * milestone, by the ordering code itself.
 */
public final class OrderRules {

    /** A customer can put at most this many seats in one order. Stops one person hoarding a whole hall. */
    public static final int MAX_SEATS_PER_ORDER = 6;

    /** Anti-abuse: how many times one customer may try to create an order per minute (successful or not). */
    public static final int ORDER_ATTEMPTS_PER_MINUTE = 10;

    /** Anti-abuse: payment attempts per minute per customer (stops card-testing bots hammering the endpoint). */
    public static final int PAYMENT_ATTEMPTS_PER_MINUTE = 5;

    /** How long chosen seats stay reserved for the customer while they pay. */
    public static final Duration HOLD_DURATION = Duration.ofMinutes(10);

    /** A PAID order can be cancelled (and refunded) only while the event is at least this far away. */
    public static final Duration PAID_ORDER_CANCELLATION_NOTICE = Duration.ofHours(48);

    private OrderRules() {
    }

    /**
     * May a paid order for an event starting at {@code eventStart} still be cancelled at {@code now}?
     * Exactly 48 hours before the start is still allowed; one second later is not.
     */
    public static boolean canCancelPaidOrder(java.time.Instant eventStart, java.time.Instant now) {
        return !now.plus(PAID_ORDER_CANCELLATION_NOTICE).isAfter(eventStart);
    }
}
