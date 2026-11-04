package com.portfolio.ticketing.event;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Database access for events. Methods without {@code @Query} are "derived": Spring builds the SQL from the name.
 */
public interface EventRepository extends JpaRepository<Event, UUID> {

    Optional<Event> findByIdAndStatus(UUID id, EventStatus status);

    Optional<Event> findByIdAndOrganizerId(UUID id, UUID organizerId);

    List<Event> findByOrganizerIdOrderByStartsAtDesc(UUID organizerId);

    /**
     * The public listing: published events that have not started yet, soonest first. Served by the partial
     * index {@code events_public_listing_idx} (migration V2).
     */
    @Query("""
            SELECT e FROM Event e
            WHERE e.status = com.portfolio.ticketing.event.EventStatus.PUBLISHED
              AND e.startsAt > :now
            ORDER BY e.startsAt ASC
            """)
    Page<Event> findUpcomingPublished(@Param("now") Instant now, Pageable pageable);

    /**
     * The same listing filtered by a name fragment.
     *
     * <p>Why a SECOND query instead of one query with {@code (:query IS NULL OR ...)}? PostgreSQL cannot infer
     * the type of an untyped NULL parameter and fails with "function lower(bytea) does not exist". Two simple
     * queries chosen in Java are more robust than one clever query.
     *
     * <p>{@code :pattern} is a bound PARAMETER, so it can never be interpreted as SQL (no injection). The LIKE
     * wildcards % and _ typed by a user would still act as wildcards, so the caller escapes them with a
     * backslash and we declare that backslash as the escape character.
     */
    @Query("""
            SELECT e FROM Event e
            WHERE e.status = com.portfolio.ticketing.event.EventStatus.PUBLISHED
              AND e.startsAt > :now
              AND LOWER(e.name) LIKE LOWER(CONCAT('%', :pattern, '%')) ESCAPE '\\'
            ORDER BY e.startsAt ASC
            """)
    Page<Event> searchUpcomingPublished(@Param("now") Instant now, @Param("pattern") String pattern, Pageable pageable);

    /**
     * Loads an event the given organizer owns AND locks its row until the transaction ends
     * ({@code SELECT ... FOR UPDATE}). Used by every organizer write (add section, publish): two such writes
     * on the same event run one after the other instead of interleaving, so "publish" can never race with
     * "add section".
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Event e WHERE e.id = :id AND e.organizerId = :organizerId")
    Optional<Event> findOwnedForUpdate(@Param("id") UUID id, @Param("organizerId") UUID organizerId);
}
