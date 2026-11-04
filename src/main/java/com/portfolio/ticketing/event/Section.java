package com.portfolio.ticketing.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A priced block of seats inside an event: "Orchestra, 10 rows of 20 seats, 5000 cents each".
 * A row of {@code sections}. Its seats are generated when it is created (see {@link SeatMapService}).
 */
@Entity
@Table(name = "sections")
public class Section {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(nullable = false, length = 50)
    private String name;

    /** Price of ONE seat, in cents (never a floating-point amount). */
    @Column(name = "price_cents", nullable = false)
    private int priceCents;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(name = "seats_per_row", nullable = false)
    private int seatsPerRow;

    protected Section() {
    }

    public Section(UUID eventId, String name, int priceCents, int rowCount, int seatsPerRow) {
        this.eventId = eventId;
        this.name = name;
        this.priceCents = priceCents;
        this.rowCount = rowCount;
        this.seatsPerRow = seatsPerRow;
    }

    public int seatCount() {
        return rowCount * seatsPerRow;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getName() {
        return name;
    }

    public int getPriceCents() {
        return priceCents;
    }

    public int getRowCount() {
        return rowCount;
    }

    public int getSeatsPerRow() {
        return seatsPerRow;
    }
}
