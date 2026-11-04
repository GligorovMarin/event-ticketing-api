package com.portfolio.ticketing.availability.dto;

import com.portfolio.ticketing.event.SeatStatus;

import java.util.List;
import java.util.UUID;

/**
 * The whole seat map of an event with each seat's CURRENT status, ready to draw as a picture:
 * sections, each with rows (A, B, C ...), each with seats left to right.
 *
 * <p>The seat status shown here is the EFFECTIVE one: a hold that has lapsed is shown as AVAILABLE (see
 * availability/SeatView). Nothing about who holds or bought a seat is exposed.
 */
public record SeatMapResponse(UUID eventId, Summary summary, List<SectionSeats> sections) {

    /** Seat counts across the whole event. */
    public record Summary(int total, int available, int held, int sold) {
    }

    public record SectionSeats(UUID sectionId, String name, int priceCents, List<RowSeats> rows) {
    }

    public record RowSeats(String row, List<SeatResponse> seats) {
    }

    public record SeatResponse(UUID id, int number, SeatStatus status) {
    }
}
