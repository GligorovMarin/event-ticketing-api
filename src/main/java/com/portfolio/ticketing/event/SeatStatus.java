package com.portfolio.ticketing.event;

/**
 * The state of one seat.
 * <pre>
 *   AVAILABLE -- customer holds it --> HELD -- payment succeeds --> SOLD
 *                                        |
 *                                        +-- hold expires / order cancelled --> AVAILABLE
 * </pre>
 * The holding and selling logic arrives in a later milestone; the database already enforces the legal
 * combinations of status, order and hold date (CHECK constraints in migration V2).
 */
public enum SeatStatus {
    AVAILABLE,
    HELD,
    SOLD
}
