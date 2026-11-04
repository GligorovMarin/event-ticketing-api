package com.portfolio.ticketing.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SectionRepository extends JpaRepository<Section, UUID> {

    List<Section> findByEventIdOrderByName(UUID eventId);

    boolean existsByEventId(UUID eventId);

    /** Total seats already planned for an event (rows x seats per row, summed over its sections). */
    @Query("SELECT COALESCE(SUM(s.rowCount * s.seatsPerRow), 0) FROM Section s WHERE s.eventId = :eventId")
    long countPlannedSeats(@Param("eventId") UUID eventId);

    /** One row of the "cheapest seat per event" query. An interface projection: Spring fills it from the aliases. */
    interface EventPrice {
        UUID getEventId();

        Integer getMinPriceCents();
    }

    /**
     * The cheapest seat price of each given event, in ONE query (GROUP BY). Called once per page of the event
     * listing instead of once per event, which would be the classic "N+1 queries" mistake.
     */
    @Query("""
            SELECT s.eventId AS eventId, MIN(s.priceCents) AS minPriceCents
            FROM Section s WHERE s.eventId IN :eventIds GROUP BY s.eventId
            """)
    List<EventPrice> findCheapestPrices(@Param("eventIds") Collection<UUID> eventIds);
}
