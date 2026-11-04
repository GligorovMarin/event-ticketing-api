package com.portfolio.ticketing.event.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Body of {@code POST /events}. The event starts as a DRAFT owned by the caller; the client cannot choose the
 * status or the owner (there is simply no field for them).
 *
 * @param startsAt when the event begins, as an ISO-8601 instant with a zone, e.g. {@code 2027-03-14T19:30:00Z}
 */
public record CreateEventRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 2000) String description,
        @NotBlank @Size(max = 150) String venue,
        @NotNull @Future Instant startsAt) {

    /** Compact constructor: trim the text fields so "  Rock Night " and "Rock Night" are the same. */
    public CreateEventRequest {
        name = name == null ? null : name.trim();
        venue = venue == null ? null : venue.trim();
        description = description == null || description.isBlank() ? null : description.trim();
    }
}
