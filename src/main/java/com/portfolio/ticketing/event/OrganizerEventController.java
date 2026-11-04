package com.portfolio.ticketing.event;

import com.portfolio.ticketing.event.dto.EventDetailResponse;
import com.portfolio.ticketing.event.dto.EventSummaryResponse;
import com.portfolio.ticketing.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The organizer's own view: ALL their events, drafts included. Kept separate from the public
 * {@link EventController} so that "who is asking?" never muddles a single endpoint: public paths show only
 * published events to everyone, these paths show the caller's own events to the caller.
 *
 * <pre>
 *   GET /organizer/events        ORGANIZER   my events, any status
 *   GET /organizer/events/{id}   ORGANIZER   one of my events (404 if it is someone else's)
 * </pre>
 */
@RestController
@RequestMapping("/organizer/events")
@PreAuthorize("hasRole('ORGANIZER')")
@Tag(name = "organizer")
public class OrganizerEventController {

    private final EventService eventService;

    public OrganizerEventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    @Operation(summary = "My events (drafts included), newest start first")
    public List<EventSummaryResponse> mine(AuthenticatedUser organizer) {
        return eventService.listMine(organizer);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One of my events")
    public EventDetailResponse mine(AuthenticatedUser organizer, @PathVariable UUID id) {
        return eventService.getMine(organizer, id);
    }
}
