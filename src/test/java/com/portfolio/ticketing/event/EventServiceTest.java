package com.portfolio.ticketing.event;

import com.portfolio.ticketing.common.ConflictException;
import com.portfolio.ticketing.common.NotFoundException;
import com.portfolio.ticketing.event.dto.CreateSectionRequest;
import com.portfolio.ticketing.security.AuthenticatedUser;
import com.portfolio.ticketing.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UNIT test of the event RULES with the repositories replaced by mocks. What the real database does with them
 * (locking, unique indexes, generated seats) is proven by EventsIT.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-30T10:00:00Z");

    @Mock
    private EventRepository events;
    @Mock
    private SectionRepository sections;
    @Mock
    private SeatMapService seatMap;

    private EventService service;
    private final AuthenticatedUser organizer = new AuthenticatedUser(UUID.randomUUID(), UserRole.ORGANIZER);
    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new EventService(events, sections, seatMap, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Event draftEvent(Instant startsAt) {
        return new Event(organizer.id(), "Rock Night", null, "Arena", startsAt);
    }

    private Event publishedEvent(Instant startsAt) {
        Event event = draftEvent(startsAt);
        event.publish();
        return event;
    }

    private static CreateSectionRequest section(String name, int rows, int perRow) {
        return new CreateSectionRequest(name, 5000, rows, perRow);
    }

    @Nested
    @DisplayName("adding a section")
    class AddSection {

        @Test
        @DisplayName("locks and loads the event only through the owner's id: someone else's event is a 404")
        void foreignEventIsNotFound() {
            when(events.findOwnedForUpdate(eventId, organizer.id())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addSection(organizer, eventId, section("Orchestra", 2, 2)))
                    .isInstanceOf(NotFoundException.class);
            verify(sections, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("is refused once the event is published: the seat map is frozen")
        void frozenAfterPublish() {
            when(events.findOwnedForUpdate(eventId, organizer.id())).thenReturn(Optional.of(publishedEvent(NOW.plusSeconds(86400))));

            assertThatThrownBy(() -> service.addSection(organizer, eventId, section("Orchestra", 2, 2)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("draft");
            verify(seatMap, never()).generateSeats(any());
        }

        @Test
        @DisplayName("is refused when the event would exceed 5000 seats")
        void seatCap() {
            when(events.findOwnedForUpdate(eventId, organizer.id())).thenReturn(Optional.of(draftEvent(NOW.plusSeconds(86400))));
            when(sections.countPlannedSeats(eventId)).thenReturn(4000L);

            // 4000 already planned + 26 x 50 = 1300 more would be 5300.
            assertThatThrownBy(() -> service.addSection(organizer, eventId, section("Balcony", 26, 50)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("5000");
        }

        @Test
        @DisplayName("allows filling an event to exactly 5000 seats")
        void seatCapBoundary() {
            when(events.findOwnedForUpdate(eventId, organizer.id())).thenReturn(Optional.of(draftEvent(NOW.plusSeconds(86400))));
            when(sections.countPlannedSeats(eventId)).thenReturn(3700L); // + 1300 = 5000 exactly
            when(sections.saveAndFlush(any(Section.class))).thenAnswer(call -> call.getArgument(0));

            assertThat(service.addSection(organizer, eventId, section("Balcony", 26, 50)).seatCount()).isEqualTo(1300);
        }

        @Test
        @DisplayName("a duplicate section name (the unique index fired) becomes a ConflictException and creates no seats")
        void duplicateName() {
            when(events.findOwnedForUpdate(eventId, organizer.id())).thenReturn(Optional.of(draftEvent(NOW.plusSeconds(86400))));
            when(sections.saveAndFlush(any(Section.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

            assertThatThrownBy(() -> service.addSection(organizer, eventId, section("Orchestra", 2, 2)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Orchestra");
            verify(seatMap, never()).generateSeats(any());
        }

        @Test
        @DisplayName("generates the seats of the section it just saved")
        void generatesSeats() {
            when(events.findOwnedForUpdate(eventId, organizer.id())).thenReturn(Optional.of(draftEvent(NOW.plusSeconds(86400))));
            when(sections.saveAndFlush(any(Section.class))).thenAnswer(call -> call.getArgument(0));

            service.addSection(organizer, eventId, section("Orchestra", 10, 20));

            ArgumentCaptor<Section> generated = ArgumentCaptor.forClass(Section.class);
            verify(seatMap).generateSeats(generated.capture());
            assertThat(generated.getValue().seatCount()).isEqualTo(200);
            assertThat(generated.getValue().getName()).isEqualTo("Orchestra");
        }
    }

    @Nested
    @DisplayName("publishing")
    class Publish {

        @Test
        @DisplayName("needs at least one section")
        void needsASection() {
            when(events.findOwnedForUpdate(eventId, organizer.id())).thenReturn(Optional.of(draftEvent(NOW.plusSeconds(86400))));
            when(sections.existsByEventId(eventId)).thenReturn(false);

            assertThatThrownBy(() -> service.publish(organizer, eventId))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("at least one section");
        }

        @Test
        @DisplayName("is refused when the start time has already passed (judged by the injected clock)")
        void startInThePast() {
            when(events.findOwnedForUpdate(eventId, organizer.id())).thenReturn(Optional.of(draftEvent(NOW.minusSeconds(1))));
            when(sections.existsByEventId(eventId)).thenReturn(true);

            assertThatThrownBy(() -> service.publish(organizer, eventId))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("already passed");
        }

        @Test
        @DisplayName("an event starting exactly now counts as passed")
        void startExactlyNow() {
            when(events.findOwnedForUpdate(eventId, organizer.id())).thenReturn(Optional.of(draftEvent(NOW)));
            when(sections.existsByEventId(eventId)).thenReturn(true);

            assertThatThrownBy(() -> service.publish(organizer, eventId)).isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("can only be done once")
        void alreadyPublished() {
            when(events.findOwnedForUpdate(eventId, organizer.id())).thenReturn(Optional.of(publishedEvent(NOW.plusSeconds(86400))));

            assertThatThrownBy(() -> service.publish(organizer, eventId)).isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("succeeds for a draft with a section and a future start")
        void succeeds() {
            Event draft = draftEvent(NOW.plusSeconds(86400));
            when(events.findOwnedForUpdate(eventId, organizer.id())).thenReturn(Optional.of(draft));
            when(sections.existsByEventId(eventId)).thenReturn(true);

            assertThat(service.publish(organizer, eventId).status()).isEqualTo(EventStatus.PUBLISHED);
        }

        @Test
        @DisplayName("someone else's event is a 404")
        void foreignEvent() {
            when(events.findOwnedForUpdate(eventId, organizer.id())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.publish(organizer, eventId)).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("browsing")
    class Browsing {

        @Test
        @DisplayName("the public listing is asked for events after NOW, using the injected clock")
        void usesTheClock() {
            when(events.findUpcomingPublished(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

            service.listPublic(null, 0, 20);

            verify(events).findUpcomingPublished(eq(NOW), any(Pageable.class));
        }

        @Test
        @DisplayName("search text selects the filtered query, with the text ESCAPED, and still uses the clock")
        void searchUsesTheFilteredQuery() {
            when(events.searchUpcomingPublished(any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

            service.listPublic("50%", 0, 20);

            verify(events).searchUpcomingPublished(eq(NOW), eq("50\\%"), any(Pageable.class));
            verify(events, never()).findUpcomingPublished(any(), any(Pageable.class));
        }

        @Test
        @DisplayName("blank search text selects the plain listing, never the filtered query")
        void blankSearchUsesThePlainQuery() {
            when(events.findUpcomingPublished(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

            service.listPublic("   ", 0, 20);

            verify(events).findUpcomingPublished(eq(NOW), any(Pageable.class));
            verify(events, never()).searchUpcomingPublished(any(), any(), any(Pageable.class));
        }

        @Test
        @DisplayName("a search for '50%' is escaped so % and _ lose their wildcard meaning")
        void searchIsEscaped() {
            assertThat(EventService.escapeLikePattern("50%")).isEqualTo("50\\%");
            assertThat(EventService.escapeLikePattern("a_b")).isEqualTo("a\\_b");
            assertThat(EventService.escapeLikePattern("back\\slash")).isEqualTo("back\\\\slash");
        }

        @Test
        @DisplayName("a blank search means no filter")
        void blankSearch() {
            assertThat(EventService.escapeLikePattern(null)).isNull();
            assertThat(EventService.escapeLikePattern("   ")).isNull();
        }

        @Test
        @DisplayName("prices for a whole page are fetched with ONE query, not one per event")
        void onePriceQueryPerPage() {
            Event first = draftEvent(NOW.plusSeconds(100));
            Event second = draftEvent(NOW.plusSeconds(200));
            when(events.findUpcomingPublished(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(first, second)));
            when(sections.findCheapestPrices(anyCollection())).thenReturn(List.of());

            service.listPublic(null, 0, 20);

            verify(sections, org.mockito.Mockito.times(1)).findCheapestPrices(anyCollection());
        }

        @Test
        @DisplayName("a draft event is invisible to the public (looked up by PUBLISHED status only)")
        void draftIsInvisible() {
            when(events.findByIdAndStatus(eventId, EventStatus.PUBLISHED)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPublic(eventId)).isInstanceOf(NotFoundException.class);
        }
    }
}
