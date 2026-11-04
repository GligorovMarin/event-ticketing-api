package com.portfolio.ticketing.report;

import com.portfolio.ticketing.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * {@code GET /organizer/events/{id}/sales}: sales numbers for one of the organizer's own events.
 * Organizers only; someone else's event is a 404.
 */
@RestController
@RequestMapping("/organizer/events/{eventId}/sales")
@PreAuthorize("hasRole('ORGANIZER')")
@Tag(name = "organizer")
public class SalesReportController {

    private final SalesReportService salesReportService;

    public SalesReportController(SalesReportService salesReportService) {
        this.salesReportService = salesReportService;
    }

    @GetMapping
    @Operation(summary = "Sales report of one of my events",
            description = "Seats sold, held and available; revenue; orders by status; and a breakdown per section.")
    public SalesReportResponse sales(AuthenticatedUser organizer, @PathVariable UUID eventId) {
        return salesReportService.report(organizer, eventId);
    }
}
