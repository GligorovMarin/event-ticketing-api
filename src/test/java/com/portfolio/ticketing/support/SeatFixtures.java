package com.portfolio.ticketing.support;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Puts individual seats into a given state for a test, writing straight to the database.
 *
 * <p>A seat is named the way a ticket names it: row letter + number, e.g. "C7", within a section.
 *
 * <p>The database requires a HELD or SOLD seat to belong to a REAL order (foreign key + CHECK constraints), so each
 * call creates a throwaway customer and an order for the seats. One call = one order, so seats given together
 * belong to the same order.
 */
@Component
@Profile("test")
public class SeatFixtures {

    private final JdbcTemplate jdbc;

    public SeatFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Marks the seats SOLD, as part of a PAID order. */
    public void sell(UUID sectionId, String... labels) {
        UUID orderId = newOrder(sectionId, "PAID", Instant.now().plus(Duration.ofMinutes(10)));
        for (String label : labels) {
            update(sectionId, label, "SOLD", orderId, null);
        }
    }

    /** Marks the seats HELD until {@code holdExpiresAt}, as part of a PENDING order. */
    public void hold(UUID sectionId, Instant holdExpiresAt, String... labels) {
        UUID orderId = newOrder(sectionId, "PENDING", holdExpiresAt);
        for (String label : labels) {
            update(sectionId, label, "HELD", orderId, holdExpiresAt);
        }
    }

    /** The id of a seat, found by its ticket-style label. */
    public UUID seatId(UUID sectionId, String label) {
        return jdbc.queryForObject("SELECT id FROM seats WHERE section_id = ? AND row_label = ? AND seat_number = ?",
                UUID.class, sectionId, label.substring(0, 1), Integer.parseInt(label.substring(1)));
    }

    /** The stored status of a seat ("AVAILABLE", "HELD", "SOLD"), without any lazy-expiry interpretation. */
    public String storedStatus(UUID sectionId, String label) {
        return jdbc.queryForObject("SELECT status FROM seats WHERE section_id = ? AND row_label = ? AND seat_number = ?",
                String.class, sectionId, label.substring(0, 1), Integer.parseInt(label.substring(1)));
    }

    /** The order currently holding or owning a seat, or null. */
    public UUID orderOf(UUID sectionId, String label) {
        return jdbc.queryForObject("SELECT order_id FROM seats WHERE section_id = ? AND row_label = ? AND seat_number = ?",
                UUID.class, sectionId, label.substring(0, 1), Integer.parseInt(label.substring(1)));
    }

    private UUID newOrder(UUID sectionId, String status, Instant holdExpiresAt) {
        UUID customerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        // A fresh customer per order, because a customer may only have ONE pending order per event.
        jdbc.update("INSERT INTO users (id, email, full_name, password_hash, role) VALUES (?, ?, 'Fixture', 'x', 'CUSTOMER')",
                customerId, "fixture-" + customerId + "@test.local");
        jdbc.update("""
                INSERT INTO orders (id, customer_id, event_id, status, total_cents, hold_expires_at)
                SELECT ?, ?, s.event_id, ?, 0, ? FROM sections s WHERE s.id = ?
                """, orderId, customerId, status, Timestamp.from(holdExpiresAt), sectionId);
        return orderId;
    }

    private void update(UUID sectionId, String label, String status, UUID orderId, Instant holdExpiresAt) {
        int changed = jdbc.update("""
                UPDATE seats SET status = ?, order_id = ?, hold_expires_at = ?
                WHERE section_id = ? AND row_label = ? AND seat_number = ?
                """, status, orderId, holdExpiresAt == null ? null : Timestamp.from(holdExpiresAt),
                sectionId, label.substring(0, 1), Integer.parseInt(label.substring(1)));

        if (changed != 1) {
            throw new IllegalArgumentException("No seat " + label + " in section " + sectionId);
        }
    }
}
