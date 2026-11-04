package com.portfolio.ticketing.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Database access for seats. The ORDER BY clauses return seats in "seat-map order" (section, then front row
 * first, then left to right), which the availability view and the adjacent-seat search rely on. They are served
 * straight from the indexes created in migration V2.
 */
public interface SeatRepository extends JpaRepository<Seat, UUID> {

    /** Every seat of an event in ONE query (up to 5000 rows): the whole availability map. */
    @Query("SELECT s FROM Seat s WHERE s.eventId = :eventId ORDER BY s.sectionId, s.rowLabel, s.seatNumber")
    List<Seat> findAllForEvent(@Param("eventId") UUID eventId);

    /** Every seat of one section (at most 1300 rows). */
    @Query("SELECT s FROM Seat s WHERE s.sectionId = :sectionId ORDER BY s.rowLabel, s.seatNumber")
    List<Seat> findAllForSection(@Param("sectionId") UUID sectionId);
}
