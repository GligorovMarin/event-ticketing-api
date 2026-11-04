package com.portfolio.ticketing.availability;

import com.portfolio.ticketing.availability.dto.SeatMapResponse;
import com.portfolio.ticketing.availability.dto.SeatSuggestionResponse;
import com.portfolio.ticketing.order.OrderRules;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * HTTP layer for seat availability. Both endpoints are PUBLIC (config/SecurityConfig lists {@code GET /events/**}):
 * anyone may look at a seat map before deciding to create an account.
 *
 * <pre>
 *   GET /events/{id}/seats                                  the whole seat map with each seat's current status
 *   GET /events/{id}/seats/suggestions?sectionId=&amp;count=4    the best block of N seats together
 * </pre>
 */
@RestController
@RequestMapping("/events/{eventId}/seats")
@Tag(name = "availability")
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    public AvailabilityController(AvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    @GetMapping
    @Operation(summary = "The seat map of a published event, with the current status of every seat",
            description = "AVAILABLE, HELD (reserved by someone who is paying) or SOLD. A hold that has run out is shown as AVAILABLE.")
    public SeatMapResponse seatMap(@PathVariable UUID eventId) {
        return availabilityService.seatMap(eventId);
    }

    @GetMapping("/suggestions")
    @Operation(summary = "Find N seats together",
            description = "The best block of adjacent free seats in one row of the section: front row first, "
                    + "then nearest the middle of the row. Empty `seats` means no row has that many free seats side by side.")
    public SeatSuggestionResponse suggest(
            @PathVariable UUID eventId,
            @RequestParam UUID sectionId,
            @RequestParam @Min(1) @Max(OrderRules.MAX_SEATS_PER_ORDER) int count) {
        return availabilityService.suggest(eventId, sectionId, count);
    }
}
