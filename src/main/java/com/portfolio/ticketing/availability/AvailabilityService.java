package com.portfolio.ticketing.availability;

import com.portfolio.ticketing.availability.dto.SeatMapResponse;
import com.portfolio.ticketing.availability.dto.SeatMapResponse.RowSeats;
import com.portfolio.ticketing.availability.dto.SeatMapResponse.SeatResponse;
import com.portfolio.ticketing.availability.dto.SeatMapResponse.SectionSeats;
import com.portfolio.ticketing.availability.dto.SeatMapResponse.Summary;
import com.portfolio.ticketing.availability.dto.SeatSuggestionResponse;
import com.portfolio.ticketing.availability.dto.SeatSuggestionResponse.SuggestedSeat;
import com.portfolio.ticketing.common.NotFoundException;
import com.portfolio.ticketing.event.EventRepository;
import com.portfolio.ticketing.event.EventStatus;
import com.portfolio.ticketing.event.SeatRepository;
import com.portfolio.ticketing.event.SeatStatus;
import com.portfolio.ticketing.event.Section;
import com.portfolio.ticketing.event.SectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reads seat availability: the full map, and the "N seats together" suggestion.
 *
 * <p>Read-only by design (every method is {@code readOnly = true}): nothing here changes a seat. Reserving
 * seats is the job of the order code, which must lock rows; showing seats must never block anyone.
 *
 * <p>Connections: {@link AvailabilityController} calls it; it reads through {@link SeatRepository} /
 * {@link SectionRepository} / {@link EventRepository}; "now" is the injected {@link Clock}; the pure rules are
 * in {@link SeatView} (lazy expiry) and {@link AdjacentSeatFinder} (the algorithm).
 */
@Service
public class AvailabilityService {

    private final EventRepository events;
    private final SectionRepository sections;
    private final SeatRepository seats;
    private final Clock clock;

    public AvailabilityService(EventRepository events, SectionRepository sections, SeatRepository seats, Clock clock) {
        this.events = events;
        this.sections = sections;
        this.seats = seats;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SeatMapResponse seatMap(UUID eventId) {
        assertPublished(eventId);
        Instant now = clock.instant();

        // Three queries in total, whatever the size of the hall: the sections, and all the seats at once.
        List<Section> eventSections = sections.findByEventIdOrderByName(eventId);
        Map<UUID, List<SeatView>> seatsBySection = seats.findAllForEvent(eventId).stream()
                .map(seat -> SeatView.of(seat, now))
                // A hash map from section id to its seats, so each section finds its seats in O(1).
                .collect(Collectors.groupingBy(SeatView::sectionId));

        int available = 0;
        int held = 0;
        int sold = 0;
        List<SectionSeats> sectionResponses = new ArrayList<>();

        for (Section section : eventSections) {
            List<SeatView> sectionSeats = seatsBySection.getOrDefault(section.getId(), List.of());

            // Group the section's seats into rows. LinkedHashMap keeps rows in front-to-back order.
            Map<String, List<SeatResponse>> rows = new LinkedHashMap<>();
            for (SeatView seat : sectionSeats) {
                rows.computeIfAbsent(seat.rowLabel(), label -> new ArrayList<>())
                        .add(new SeatResponse(seat.id(), seat.seatNumber(), seat.status()));

                if (seat.status() == SeatStatus.AVAILABLE) available++;
                else if (seat.status() == SeatStatus.HELD) held++;
                else sold++;
            }

            List<RowSeats> rowResponses = rows.entrySet().stream()
                    .map(row -> new RowSeats(row.getKey(), row.getValue()))
                    .toList();
            sectionResponses.add(new SectionSeats(section.getId(), section.getName(), section.getPriceCents(), rowResponses));
        }

        Summary summary = new Summary(available + held + sold, available, held, sold);
        return new SeatMapResponse(eventId, summary, sectionResponses);
    }

    /** The best block of {@code count} adjacent free seats in the section, or an empty list if none exists. */
    @Transactional(readOnly = true)
    public SeatSuggestionResponse suggest(UUID eventId, UUID sectionId, int count) {
        assertPublished(eventId);

        // The section must belong to THIS event, otherwise a client could probe other events' sections by id.
        Section section = sections.findById(sectionId)
                .filter(candidate -> candidate.getEventId().equals(eventId))
                .orElseThrow(() -> new NotFoundException("Section " + sectionId + " not found in this event"));

        Instant now = clock.instant();
        List<SeatView> sectionSeats = seats.findAllForSection(sectionId).stream()
                .map(seat -> SeatView.of(seat, now))
                .toList();

        List<SuggestedSeat> suggested = AdjacentSeatFinder.find(sectionSeats, count)
                .orElse(List.of()).stream()
                .map(seat -> new SuggestedSeat(seat.id(), seat.rowLabel(), seat.seatNumber(), seat.rowLabel() + seat.seatNumber()))
                .toList();

        int total = suggested.size() * section.getPriceCents();
        return new SeatSuggestionResponse(sectionId, count, suggested, total);
    }

    /** Only PUBLISHED events have a public seat map. A draft or cancelled event is "not found". */
    private void assertPublished(UUID eventId) {
        events.findByIdAndStatus(eventId, EventStatus.PUBLISHED)
                .orElseThrow(() -> new NotFoundException("Event " + eventId + " not found"));
    }
}
