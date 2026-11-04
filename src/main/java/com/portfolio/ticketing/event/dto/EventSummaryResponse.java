package com.portfolio.ticketing.event.dto;

import com.portfolio.ticketing.event.Event;
import com.portfolio.ticketing.event.EventStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * An event as one line of a list. {@code fromPriceCents} is the cheapest seat ("tickets from 25.00"), or null
 * while the event has no sections yet.
 */
public record EventSummaryResponse(
        UUID id, String name, String venue, Instant startsAt, EventStatus status, Integer fromPriceCents) {

    public static EventSummaryResponse from(Event event, Integer fromPriceCents) {
        return new EventSummaryResponse(event.getId(), event.getName(), event.getVenue(), event.getStartsAt(),
                event.getStatus(), fromPriceCents);
    }
}
