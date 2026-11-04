package com.portfolio.ticketing.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One physical seat. A row of {@code seats}.
 *
 * <p>Seats are created in bulk by {@link SeatMapService} with a JDBC batch insert (one round trip for
 * hundreds of rows), so this entity has no generated id and no public constructor: it exists to READ seats
 * (availability map) and, in a later milestone, to LOCK and update them when customers hold seats.
 */
@Entity
@Table(name = "seats")
public class Seat {

    @Id
    private UUID id;

    @Column(name = "section_id", nullable = false, updatable = false)
    private UUID sectionId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "row_label", nullable = false, updatable = false, length = 3)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false, updatable = false)
    private int seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatStatus status;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    protected Seat() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getSectionId() {
        return sectionId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getRowLabel() {
        return rowLabel;
    }

    public int getSeatNumber() {
        return seatNumber;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }
}
