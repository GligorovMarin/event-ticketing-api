package com.portfolio.ticketing.report;

import java.util.List;
import java.util.UUID;

/**
 * An organizer's sales report for one event.
 *
 * <p>All numbers describe the situation RIGHT NOW: a seat released by a cancellation is no longer counted as sold,
 * and its money no longer counts as revenue (the customer was refunded).
 *
 * @param revenueCents sum of the prices of the seats currently sold, in cents
 * @param held         seats currently reserved by customers who are paying (a hold that ran out is not counted)
 * @param orders       how many orders the event has, by status (a pending order whose hold ended counts as expired)
 */
public record SalesReportResponse(
        UUID eventId,
        String eventName,
        int capacity,
        int sold,
        int held,
        int available,
        long revenueCents,
        OrderCounts orders,
        List<SectionSales> sections) {

    public record OrderCounts(int paid, int pending, int cancelled, int expired) {
    }

    /** {@code soldPercent} is rounded to one decimal place, e.g. 37.5. */
    public record SectionSales(UUID sectionId, String name, int priceCents, int capacity, int sold,
                               long revenueCents, double soldPercent) {
    }
}
