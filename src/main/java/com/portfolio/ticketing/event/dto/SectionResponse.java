package com.portfolio.ticketing.event.dto;

import com.portfolio.ticketing.event.Section;

import java.util.UUID;

public record SectionResponse(UUID id, String name, int priceCents, int rows, int seatsPerRow, int seatCount) {

    public static SectionResponse from(Section section) {
        return new SectionResponse(section.getId(), section.getName(), section.getPriceCents(),
                section.getRowCount(), section.getSeatsPerRow(), section.seatCount());
    }
}
