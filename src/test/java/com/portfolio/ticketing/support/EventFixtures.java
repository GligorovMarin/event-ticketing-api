package com.portfolio.ticketing.support;

import com.portfolio.ticketing.event.EventService;
import com.portfolio.ticketing.event.dto.CreateEventRequest;
import com.portfolio.ticketing.event.dto.CreateSectionRequest;
import com.portfolio.ticketing.event.dto.EventDetailResponse;
import com.portfolio.ticketing.event.dto.SectionResponse;
import com.portfolio.ticketing.security.AuthenticatedUser;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds events for tests through the REAL service code (EventService), skipping the HTTP layer. A test that is
 * about seats should not repeat ten lines of "create event, add sections, publish" in every method.
 */
@Component
@Profile("test")
public class EventFixtures {

    private final EventService eventService;

    public EventFixtures(EventService eventService) {
        this.eventService = eventService;
    }

    /** What to create: a section name, its price, and its size. */
    public record SectionSpec(String name, int priceCents, int rows, int seatsPerRow) {
    }

    /** The created event and the ids of its sections by name. */
    public record BuiltEvent(UUID eventId, Map<String, UUID> sectionIds) {

        public UUID section(String name) {
            return sectionIds.get(name);
        }
    }

    public BuiltEvent draft(TestAccounts.Account organizer, String name, int daysAhead, SectionSpec... sections) {
        AuthenticatedUser caller = new AuthenticatedUser(organizer.id(), organizer.role());

        EventDetailResponse event = eventService.create(caller, new CreateEventRequest(
                name, "A fine show", "City Arena", Instant.now().plus(Duration.ofDays(daysAhead))));

        Map<String, UUID> sectionIds = new LinkedHashMap<>();
        for (SectionSpec spec : List.of(sections)) {
            SectionResponse created = eventService.addSection(caller, event.id(),
                    new CreateSectionRequest(spec.name(), spec.priceCents(), spec.rows(), spec.seatsPerRow()));
            sectionIds.put(spec.name(), created.id());
        }
        return new BuiltEvent(event.id(), sectionIds);
    }

    public BuiltEvent published(TestAccounts.Account organizer, String name, int daysAhead, SectionSpec... sections) {
        BuiltEvent built = draft(organizer, name, daysAhead, sections);
        eventService.publish(new AuthenticatedUser(organizer.id(), organizer.role()), built.eventId());

        return built;
    }
}
