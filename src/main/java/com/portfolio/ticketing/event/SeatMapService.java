package com.portfolio.ticketing.event;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Creates the physical seats of a section.
 *
 * <p>WHY JDBC AND NOT JPA HERE. A section of 20 rows x 25 seats is 500 rows. Saving 500 entities through
 * Hibernate means 500 objects tracked in memory and (without special tuning) 500 separate INSERTs. A JDBC
 * <b>batch</b> sends all rows to the database in one go. Hibernate stays in charge of everything that reads
 * and updates single objects; bulk loading is the place to bypass it. Both use the SAME transaction and
 * connection (Spring ties them together), so seats and section are saved atomically.
 *
 * <p>Called by {@link EventService#addSection}; layout rules in {@link SeatMapLayout}.
 */
@Service
public class SeatMapService {

    private final JdbcTemplate jdbc;

    public SeatMapService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts every seat of the section as AVAILABLE. MUST run inside the transaction that created the section. */
    @Transactional
    public int generateSeats(Section section) {
        List<SeatMapLayout.SeatPosition> positions =
                SeatMapLayout.positions(section.getRowCount(), section.getSeatsPerRow());

        List<Object[]> rows = positions.stream()
                .map(position -> new Object[]{
                        UUID.randomUUID(), section.getId(), section.getEventId(),
                        position.rowLabel(), position.seatNumber()})
                .toList();

        // One statement, many parameter sets: the batch.
        jdbc.batchUpdate("""
                INSERT INTO seats (id, section_id, event_id, row_label, seat_number, status)
                VALUES (?, ?, ?, ?, ?, 'AVAILABLE')
                """, rows);

        return rows.size();
    }
}
