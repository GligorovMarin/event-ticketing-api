package com.portfolio.ticketing.support;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Puts individual seats into a given state for a test, writing straight to the database.
 *
 * <p>A seat is named the way a ticket names it: row letter + number, e.g. "C7", within a section. The database
 * requires a HELD or SOLD seat to belong to an order (CHECK constraint in V2), so these helpers attach a
 * made-up order id for now. (When real orders exist, only THIS class needs to change.)
 */
@Component
@Profile("test")
public class SeatFixtures {

    private final JdbcTemplate jdbc;

    public SeatFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void sell(UUID sectionId, String... labels) {
        for (String label : labels) {
            update(sectionId, label, "SOLD", null);
        }
    }

    public void hold(UUID sectionId, Instant holdExpiresAt, String... labels) {
        for (String label : labels) {
            update(sectionId, label, "HELD", holdExpiresAt);
        }
    }

    private void update(UUID sectionId, String label, String status, Instant holdExpiresAt) {
        String row = label.substring(0, 1);
        int number = Integer.parseInt(label.substring(1));

        int changed = jdbc.update("""
                UPDATE seats SET status = ?, order_id = gen_random_uuid(), hold_expires_at = ?
                WHERE section_id = ? AND row_label = ? AND seat_number = ?
                """, status, holdExpiresAt == null ? null : Timestamp.from(holdExpiresAt), sectionId, row, number);

        if (changed != 1) {
            throw new IllegalArgumentException("No seat " + label + " in section " + sectionId);
        }
    }
}
