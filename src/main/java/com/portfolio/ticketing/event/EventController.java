package com.portfolio.ticketing.event;

import com.portfolio.ticketing.common.PageResponse;
import com.portfolio.ticketing.event.dto.CreateEventRequest;
import com.portfolio.ticketing.event.dto.CreateSectionRequest;
import com.portfolio.ticketing.event.dto.EventDetailResponse;
import com.portfolio.ticketing.event.dto.EventSummaryResponse;
import com.portfolio.ticketing.event.dto.SectionResponse;
import com.portfolio.ticketing.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * HTTP layer for events. No business logic: see {@link EventService}.
 *
 * <pre>
 *   GET  /events                     PUBLIC     upcoming published events (paged, searchable)
 *   GET  /events/{id}                PUBLIC     one published event with its sections and prices
 *   POST /events                     ORGANIZER  create a draft event
 *   POST /events/{id}/sections       ORGANIZER  add a block of seats to your draft event
 *   POST /events/{id}/publish        ORGANIZER  put your event on sale
 * </pre>
 * The two GET paths are public because config/SecurityConfig lists {@code GET /events/**} as public. The POSTs
 * need a token, and {@code @PreAuthorize} then restricts them to organizers.
 */
@RestController
// No class-level @Validated: Spring validates @Min/@Max on parameters by itself and answers 400. Adding it would
// switch to a different mechanism that throws ConstraintViolationException.
@RequestMapping("/events")
@Tag(name = "events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    @Operation(summary = "Browse upcoming published events",
            description = "Soonest first. `q` filters by a fragment of the name; `page` is zero-based.")
    public PageResponse<EventSummaryResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return eventService.listPublic(q, page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One published event with its sections and prices")
    public EventDetailResponse get(@PathVariable UUID id) {
        return eventService.getPublic(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ORGANIZER')")
    @Operation(summary = "Create a draft event (organizers only)")
    public EventDetailResponse create(AuthenticatedUser organizer, @Valid @RequestBody CreateEventRequest request) {
        return eventService.create(organizer, request);
    }

    @PostMapping("/{id}/sections")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ORGANIZER')")
    @Operation(summary = "Add a block of seats to your draft event (organizers only)",
            description = "Generates rows x seatsPerRow seats. Only possible while the event is a draft.")
    public SectionResponse addSection(AuthenticatedUser organizer, @PathVariable UUID id,
                                      @Valid @RequestBody CreateSectionRequest request) {
        return eventService.addSection(organizer, id, request);
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('ORGANIZER')")
    @Operation(summary = "Put your event on sale (organizers only)",
            description = "Needs at least one section and a start time in the future. Freezes the seat map.")
    public EventDetailResponse publish(AuthenticatedUser organizer, @PathVariable UUID id) {
        return eventService.publish(organizer, id);
    }
}
