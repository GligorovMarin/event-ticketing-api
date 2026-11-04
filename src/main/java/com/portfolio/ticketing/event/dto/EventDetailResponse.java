package com.portfolio.ticketing.event.dto;

import com.portfolio.ticketing.event.Event;
import com.portfolio.ticketing.event.EventStatus;
import com.portfolio.ticketing.event.Section;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One event with its sections (the seat map's structure and prices, not the individual seats). */
public record EventDetailResponse(
        UUID id, String name, String description, String venue, Instant startsAt, EventStatus status,
        List<SectionResponse> sections) {

    public static EventDetailResponse from(Event event, List<Section> sections) {
        return new EventDetailResponse(event.getId(), event.getName(), event.getDescription(), event.getVenue(),
                event.getStartsAt(), event.getStatus(), sections.stream().map(SectionResponse::from).toList());
    }
}
