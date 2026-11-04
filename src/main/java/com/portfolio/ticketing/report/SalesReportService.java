package com.portfolio.ticketing.report;

import com.portfolio.ticketing.common.NotFoundException;
import com.portfolio.ticketing.event.Event;
import com.portfolio.ticketing.event.EventRepository;
import com.portfolio.ticketing.report.SalesReportResponse.OrderCounts;
import com.portfolio.ticketing.report.SalesReportResponse.SectionSales;
import com.portfolio.ticketing.security.AuthenticatedUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The organizer's sales numbers, computed BY THE DATABASE.
 *
 * <p>Totals like "sold per section" or "revenue" are exactly what SQL aggregation ({@code GROUP BY}, {@code COUNT},
 * {@code SUM}) is for: PostgreSQL reads the rows next to the data and returns a handful of numbers. The alternative,
 * loading 5000 seat objects into Java and adding them up in a loop, would move far more data for the same answer. For
 * this kind of read-only report plain SQL through {@link JdbcTemplate} is clearer than JPA.
 *
 * <p>Three queries in total, whatever the size of the event. Connected to: {@link SalesReportController}.
 */
@Service
public class SalesReportService {

    private final EventRepository events;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SalesReportService(EventRepository events, JdbcTemplate jdbc, Clock clock) {
        this.events = events;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SalesReportResponse report(AuthenticatedUser organizer, UUID eventId) {
        // Another organizer's event looks like a missing one (404), as everywhere in this API.
        Event event = events.findByIdAndOrganizerId(eventId, organizer.id())
                .orElseThrow(() -> new NotFoundException("Event " + eventId + " not found"));
        Timestamp now = Timestamp.from(clock.instant());

        List<SectionSales> sections = salesPerSection(eventId);
        int held = heldSeats(eventId, now);
        OrderCounts orderCounts = orderCounts(eventId, now);

        int capacity = sections.stream().mapToInt(SectionSales::capacity).sum();
        int sold = sections.stream().mapToInt(SectionSales::sold).sum();
        long revenue = sections.stream().mapToLong(SectionSales::revenueCents).sum();

        return new SalesReportResponse(eventId, event.getName(), capacity, sold, held, capacity - sold - held,
                revenue, orderCounts, sections);
    }

    /**
     * One row per section: capacity, seats sold and money taken. The LEFT JOINs keep sections with no sales at all
     * (a plain JOIN would silently drop them from the report); COUNT(seat.id) counts only the joined rows that exist.
     * order_items is joined on BOTH the seat and the seat's current order, so a seat's price comes from the order that
     * actually owns it now (the price copied at order time, not today's section price).
     */
    private List<SectionSales> salesPerSection(UUID eventId) {
        return jdbc.query("""
                SELECT s.id, s.name, s.price_cents,
                       s.row_count * s.seats_per_row   AS capacity,
                       COUNT(seat.id)                  AS sold,
                       COALESCE(SUM(oi.price_cents), 0) AS revenue
                FROM sections s
                LEFT JOIN seats seat ON seat.section_id = s.id AND seat.status = 'SOLD'
                LEFT JOIN order_items oi ON oi.seat_id = seat.id AND oi.order_id = seat.order_id
                WHERE s.event_id = ?
                GROUP BY s.id, s.name, s.price_cents, s.row_count, s.seats_per_row
                ORDER BY s.name
                """,
                (row, rowNumber) -> {
                    int capacity = row.getInt("capacity");
                    int sold = row.getInt("sold");
                    // Round to one decimal: 3 of 8 seats = 37.5 %.
                    double percent = capacity == 0 ? 0.0 : Math.round(sold * 1000.0 / capacity) / 10.0;
                    return new SectionSales(row.getObject("id", UUID.class), row.getString("name"), row.getInt("price_cents"),
                            capacity, sold, row.getLong("revenue"), percent);
                },
                eventId);
    }

    /** Seats reserved right now. A HELD seat whose hold has ended is available again and is not counted. */
    private int heldSeats(UUID eventId, Timestamp now) {
        Integer held = jdbc.queryForObject(
                "SELECT count(*) FROM seats WHERE event_id = ? AND status = 'HELD' AND hold_expires_at > ?",
                Integer.class, eventId, now);
        return held == null ? 0 : held;
    }

    /**
     * Orders per EFFECTIVE status in one grouped query. The CASE expression applies lazy expiry in SQL: a PENDING
     * order whose hold has ended counts as EXPIRED even if the sweep has not written that down yet.
     */
    private OrderCounts orderCounts(UUID eventId, Timestamp now) {
        int[] counts = new int[4]; // paid, pending, cancelled, expired
        jdbc.query("""
                SELECT CASE WHEN status = 'PENDING' AND hold_expires_at <= ? THEN 'EXPIRED' ELSE status END AS effective_status,
                       count(*) AS orders
                FROM orders WHERE event_id = ?
                GROUP BY 1
                """,
                row -> {
                    int number = row.getInt("orders");
                    switch (row.getString("effective_status")) {
                        case "PAID" -> counts[0] = number;
                        case "PENDING" -> counts[1] = number;
                        case "CANCELLED" -> counts[2] = number;
                        case "EXPIRED" -> counts[3] = number;
                        default -> throw new IllegalStateException("Unexpected order status in the database");
                    }
                },
                now, eventId);
        return new OrderCounts(counts[0], counts[1], counts[2], counts[3]);
    }
}
