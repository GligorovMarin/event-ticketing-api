package com.portfolio.ticketing.order;

/**
 * Where an order is in its life.
 * <pre>
 *   PENDING --pay--> PAID --cancel (until 48 h before the event)--> CANCELLED
 *      |
 *      +-- hold runs out --> EXPIRED
 *      +-- customer cancels --> CANCELLED
 * </pre>
 * PENDING means the seats are held for the customer while they pay. See {@link Order#effectiveStatus}.
 */
public enum OrderStatus {
    PENDING,
    PAID,
    CANCELLED,
    EXPIRED
}
