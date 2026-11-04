package com.portfolio.ticketing.event;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /**
     * Loads the given seats and LOCKS their rows ({@code SELECT ... FOR UPDATE}) until the transaction ends.
     * Any other transaction that wants one of these seats must wait.
     *
     * <p><b>ORDER BY id is the deadlock prevention.</b> Suppose customer 1 wants seats [A1, A2] and customer 2
     * wants [A2, A1]. If each locked "in the order requested", customer 1 could hold A1 and wait for A2 while
     * customer 2 holds A2 and waits for A1: a deadlock. If EVERY transaction locks in the same global order (here:
     * ascending id), the later one simply waits for the first to finish. Always lock in a consistent order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.id IN :ids ORDER BY s.id")
    List<Seat> lockAllByIdOrdered(@Param("ids") Collection<UUID> ids);

    /** Every seat of one section (at most 1300 rows). */
    @Query("SELECT s FROM Seat s WHERE s.sectionId = :sectionId ORDER BY s.rowLabel, s.seatNumber")
    List<Seat> findAllForSection(@Param("sectionId") UUID sectionId);
}
