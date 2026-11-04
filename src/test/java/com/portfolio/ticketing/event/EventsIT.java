package com.portfolio.ticketing.event;

import com.jayway.jsonpath.JsonPath;
import com.portfolio.ticketing.support.DatabaseCleaner;
import com.portfolio.ticketing.support.IntegrationTest;
import com.portfolio.ticketing.support.TestAccounts;
import com.portfolio.ticketing.support.TestAccounts.Account;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * INTEGRATION test of events and seat maps: real application, real PostgreSQL, real JWTs.
 * Read it in four parts: building an event (organizer), publishing rules, ownership, public browsing.
 */
@IntegrationTest
class EventsIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DatabaseCleaner cleaner;
    @Autowired
    private TestAccounts accounts;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Account organizer;
    private Account otherOrganizer;
    private Account customer;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        organizer = accounts.organizer("org@example.com");
        otherOrganizer = accounts.organizer("other-org@example.com");
        customer = accounts.customer("ana@example.com");
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static String inDays(int days) {
        return Instant.now().plus(Duration.ofDays(days)).toString();
    }

    private ResultActions createEventRequest(Account as, String name, String startsAt) throws Exception {
        return mockMvc.perform(post("/events")
                .header("Authorization", as.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"%s","description":"A fine show","venue":"City Arena","startsAt":"%s"}
                        """.formatted(name, startsAt)));
    }

    private UUID createEvent(Account as, String name, int daysAhead) throws Exception {
        String body = createEventRequest(as, name, inDays(daysAhead))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions addSectionRequest(Account as, UUID eventId, String name, int priceCents, int rows, int seatsPerRow) throws Exception {
        return mockMvc.perform(post("/events/{id}/sections", eventId)
                .header("Authorization", as.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"%s","priceCents":%d,"rows":%d,"seatsPerRow":%d}
                        """.formatted(name, priceCents, rows, seatsPerRow)));
    }

    private ResultActions publishRequest(Account as, UUID eventId) throws Exception {
        return mockMvc.perform(post("/events/{id}/publish", eventId).header("Authorization", as.bearer()));
    }

    /** An event that is on sale: created, given one section, published. */
    private UUID publishedEvent(Account as, String name, int daysAhead, int priceCents) throws Exception {
        UUID id = createEvent(as, name, daysAhead);
        addSectionRequest(as, id, "Floor", priceCents, 2, 5).andExpect(status().isCreated());
        publishRequest(as, id).andExpect(status().isOk());
        return id;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    // ============================================================================================
    @Nested
    @DisplayName("creating an event")
    class Creating {

        @Test
        @DisplayName("an organizer creates a DRAFT event owned by them")
        void organizerCreatesDraft() throws Exception {
            createEventRequest(organizer, "Rock Night", inDays(30))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("DRAFT"))
                    .andExpect(jsonPath("$.name").value("Rock Night"))
                    .andExpect(jsonPath("$.sections", empty()));

            assertThat(count("SELECT count(*) FROM events WHERE organizer_id = ?", organizer.id())).isEqualTo(1);
        }

        @Test
        @DisplayName("a customer cannot create events (403) and an anonymous caller gets 401")
        void onlyOrganizers() throws Exception {
            createEventRequest(customer, "Nope", inDays(30)).andExpect(status().isForbidden());
            mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"x\",\"venue\":\"y\",\"startsAt\":\"" + inDays(5) + "\"}"))
                    .andExpect(status().isUnauthorized());

            assertThat(count("SELECT count(*) FROM events")).isZero();
        }

        @ParameterizedTest(name = "rejects {0}")
        @CsvSource(delimiter = '|', textBlock = """
                a blank name         | ' '        | City Arena | 30
                a blank venue        | Rock Night | ' '        | 30
                a start in the past  | Rock Night | City Arena | -1
                """)
        void invalidInput(String description, String name, String venue, int daysAhead) throws Exception {
            mockMvc.perform(post("/events").header("Authorization", organizer.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"%s\",\"venue\":\"%s\",\"startsAt\":\"%s\"}".formatted(name.trim(), venue.trim(), inDays(daysAhead))))
                    .andExpect(status().isBadRequest());

            assertThat(count("SELECT count(*) FROM events")).isZero();
        }

        @Test
        @DisplayName("the client cannot choose the status or the owner: unknown fields are rejected")
        void cannotSetStatusOrOwner() throws Exception {
            mockMvc.perform(post("/events").header("Authorization", organizer.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"x\",\"venue\":\"y\",\"startsAt\":\"%s\",\"status\":\"PUBLISHED\",\"organizerId\":\"%s\"}"
                                    .formatted(inDays(5), otherOrganizer.id())))
                    .andExpect(status().isBadRequest());
        }
    }

    // ============================================================================================
    @Nested
    @DisplayName("sections and the generated seat map")
    class Sections {

        @Test
        @DisplayName("a section generates exactly rows x seatsPerRow seats, all AVAILABLE, in the right positions")
        void generatesSeats() throws Exception {
            UUID eventId = createEvent(organizer, "Rock Night", 30);

            addSectionRequest(organizer, eventId, "Orchestra", 5000, 3, 4)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.seatCount").value(12))
                    .andExpect(jsonPath("$.priceCents").value(5000));

            assertThat(count("SELECT count(*) FROM seats WHERE event_id = ?", eventId)).isEqualTo(12);
            assertThat(count("SELECT count(*) FROM seats WHERE event_id = ? AND status = 'AVAILABLE' AND order_id IS NULL", eventId)).isEqualTo(12);
            assertThat(count("SELECT count(DISTINCT row_label) FROM seats WHERE event_id = ?", eventId)).isEqualTo(3);

            String first = jdbc.queryForObject("SELECT row_label || seat_number FROM seats WHERE event_id = ? ORDER BY row_label, seat_number LIMIT 1", String.class, eventId);
            String last = jdbc.queryForObject("SELECT row_label || seat_number FROM seats WHERE event_id = ? ORDER BY row_label DESC, seat_number DESC LIMIT 1", String.class, eventId);
            assertThat(first).isEqualTo("A1");
            assertThat(last).isEqualTo("C4");
        }

        @Test
        @DisplayName("the largest section (26 x 50 = 1300 seats) is created in one request")
        void largestSection() throws Exception {
            UUID eventId = createEvent(organizer, "Stadium Show", 30);

            addSectionRequest(organizer, eventId, "Stand", 2500, 26, 50).andExpect(status().isCreated());

            assertThat(count("SELECT count(*) FROM seats WHERE event_id = ?", eventId)).isEqualTo(1300);
        }

        @Test
        @DisplayName("a duplicate section name is a 409 and creates NO extra seats (the whole request rolled back)")
        void duplicateNameIsAtomic() throws Exception {
            UUID eventId = createEvent(organizer, "Rock Night", 30);
            addSectionRequest(organizer, eventId, "Orchestra", 5000, 2, 2).andExpect(status().isCreated());

            addSectionRequest(organizer, eventId, "Orchestra", 9000, 5, 5).andExpect(status().isConflict());

            assertThat(count("SELECT count(*) FROM sections WHERE event_id = ?", eventId)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM seats WHERE event_id = ?", eventId)).isEqualTo(4);
        }

        @Test
        @DisplayName("five simultaneous requests for the same section name: exactly one wins, seats are created once")
        void concurrentDuplicateName() throws Exception {
            UUID eventId = createEvent(organizer, "Rock Night", 30);
            var pool = Executors.newFixedThreadPool(5);
            try {
                List<Future<Integer>> results = new ArrayList<>();
                for (int i = 0; i < 5; i++) {
                    results.add(pool.submit(() -> addSectionRequest(organizer, eventId, "Orchestra", 5000, 4, 5)
                            .andReturn().getResponse().getStatus()));
                }

                int created = 0;
                int conflicts = 0;
                for (Future<Integer> result : results) {
                    int status = result.get();
                    if (status == 201) created++;
                    if (status == 409) conflicts++;
                }

                assertThat(created).isEqualTo(1);
                assertThat(conflicts).isEqualTo(4);
                assertThat(count("SELECT count(*) FROM seats WHERE event_id = ?", eventId)).isEqualTo(20);
            } finally {
                pool.shutdownNow();
            }
        }

        @ParameterizedTest(name = "rejects {0}")
        @CsvSource(delimiter = '|', textBlock = """
                27 rows          | 27 | 10 | 1000
                51 seats per row | 5  | 51 | 1000
                0 rows           | 0  | 10 | 1000
                a negative price | 5  | 10 | -1
                """)
        void invalidSection(String description, int rows, int perRow, int price) throws Exception {
            UUID eventId = createEvent(organizer, "Rock Night", 30);

            addSectionRequest(organizer, eventId, "Bad", price, rows, perRow).andExpect(status().isBadRequest());

            assertThat(count("SELECT count(*) FROM sections")).isZero();
        }

        @Test
        @DisplayName("an event cannot exceed 5000 seats: three 1300-seat sections fit, a fourth is refused")
        void seatCapPerEvent() throws Exception {
            UUID eventId = createEvent(organizer, "Stadium Show", 30);
            for (String name : List.of("North", "South", "East")) {
                addSectionRequest(organizer, eventId, name, 1000, 26, 50).andExpect(status().isCreated());
            }

            // 3 x 1300 = 3900 seats so far; another 1300 would make 5200.
            addSectionRequest(organizer, eventId, "West", 1000, 26, 50)
                    .andExpect(status().isConflict());

            // 3900 + 1100 = 5000 exactly is allowed (22 rows x 50).
            addSectionRequest(organizer, eventId, "West", 1000, 22, 50).andExpect(status().isCreated());
            assertThat(count("SELECT count(*) FROM seats WHERE event_id = ?", eventId)).isEqualTo(5000);
        }

        @Test
        @DisplayName("customers cannot add sections (403)")
        void customersCannot() throws Exception {
            UUID eventId = createEvent(organizer, "Rock Night", 30);

            addSectionRequest(customer, eventId, "Orchestra", 5000, 2, 2).andExpect(status().isForbidden());
        }
    }

    // ============================================================================================
    @Nested
    @DisplayName("ownership: an event is visible to its organizer only")
    class Ownership {

        @Test
        @DisplayName("another organizer gets 404 (not 403) when touching your event")
        void foreignEventLooksNonExistent() throws Exception {
            UUID eventId = createEvent(organizer, "Rock Night", 30);

            addSectionRequest(otherOrganizer, eventId, "Orchestra", 5000, 2, 2).andExpect(status().isNotFound());
            publishRequest(otherOrganizer, eventId).andExpect(status().isNotFound());
            mockMvc.perform(get("/organizer/events/{id}", eventId).header("Authorization", otherOrganizer.bearer()))
                    .andExpect(status().isNotFound());

            assertThat(count("SELECT count(*) FROM sections")).isZero();
        }

        @Test
        @DisplayName("an unknown event id is also 404, and a malformed id is 400")
        void unknownAndMalformedIds() throws Exception {
            addSectionRequest(organizer, UUID.randomUUID(), "Orchestra", 5000, 2, 2).andExpect(status().isNotFound());
            mockMvc.perform(post("/events/not-a-uuid/publish").header("Authorization", organizer.bearer()))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("GET /organizer/events lists only the caller's events, drafts included")
        void myEvents() throws Exception {
            UUID draft = createEvent(organizer, "My Draft", 10);
            UUID published = publishedEvent(organizer, "My Show", 20, 4000);
            createEvent(otherOrganizer, "Someone Else's", 15);

            mockMvc.perform(get("/organizer/events").header("Authorization", organizer.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    // newest start first: the show in 20 days, then the draft in 10
                    .andExpect(jsonPath("$[0].id").value(published.toString()))
                    .andExpect(jsonPath("$[1].id").value(draft.toString()))
                    .andExpect(jsonPath("$[1].fromPriceCents").doesNotExist())
                    .andExpect(jsonPath("$[0].fromPriceCents").value(4000));
        }

        @Test
        @DisplayName("customers cannot use the organizer routes (403)")
        void customersCannotUseOrganizerRoutes() throws Exception {
            mockMvc.perform(get("/organizer/events").header("Authorization", customer.bearer()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/organizer/events"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============================================================================================
    @Nested
    @DisplayName("publishing")
    class Publishing {

        @Test
        @DisplayName("needs at least one section (409)")
        void needsASection() throws Exception {
            UUID eventId = createEvent(organizer, "Rock Night", 30);

            publishRequest(organizer, eventId).andExpect(status().isConflict());
        }

        @Test
        @DisplayName("puts the event on sale and freezes the seat map")
        void publishesAndFreezes() throws Exception {
            UUID eventId = createEvent(organizer, "Rock Night", 30);
            addSectionRequest(organizer, eventId, "Orchestra", 5000, 2, 2).andExpect(status().isCreated());

            publishRequest(organizer, eventId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PUBLISHED"));

            // The seat map is frozen: no more sections.
            addSectionRequest(organizer, eventId, "Balcony", 3000, 2, 2).andExpect(status().isConflict());
            // And it can only be published once.
            publishRequest(organizer, eventId).andExpect(status().isConflict());
            assertThat(count("SELECT count(*) FROM seats WHERE event_id = ?", eventId)).isEqualTo(4);
        }

        @Test
        @DisplayName("is refused if the start time passed while the event was still a draft")
        void startedInTheMeantime() throws Exception {
            UUID eventId = createEvent(organizer, "Rock Night", 30);
            addSectionRequest(organizer, eventId, "Orchestra", 5000, 2, 2).andExpect(status().isCreated());
            // Move the start into the past directly in the database, as if time had passed.
            jdbc.update("UPDATE events SET starts_at = now() - interval '1 hour' WHERE id = ?", eventId);

            publishRequest(organizer, eventId).andExpect(status().isConflict());
        }

        @Test
        @DisplayName("publish and add-section racing on the same event end consistent: seats always match sections")
        void publishVersusAddSection() throws Exception {
            UUID eventId = createEvent(organizer, "Rock Night", 30);
            addSectionRequest(organizer, eventId, "Orchestra", 5000, 2, 2).andExpect(status().isCreated());
            var pool = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> add = pool.submit(() -> addSectionRequest(organizer, eventId, "Balcony", 3000, 3, 3)
                        .andReturn().getResponse().getStatus());
                Future<Integer> publish = pool.submit(() -> publishRequest(organizer, eventId)
                        .andReturn().getResponse().getStatus());
                int addStatus = add.get();
                publish.get();

                // Whichever ran first, the result is consistent: the Balcony exists WITH its 9 seats, or not at all.
                int sections = count("SELECT count(*) FROM sections WHERE event_id = ?", eventId);
                int seats = count("SELECT count(*) FROM seats WHERE event_id = ?", eventId);
                assertThat(sections == 2 ? seats == 13 : seats == 4).isTrue();
                assertThat(addStatus == 201).isEqualTo(sections == 2);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    // ============================================================================================
    @Nested
    @DisplayName("public browsing (no login)")
    class Browsing {

        @Test
        @DisplayName("shows published events with sections and prices, and hides drafts")
        void publicSeesOnlyPublished() throws Exception {
            UUID published = publishedEvent(organizer, "Rock Night", 30, 5000);
            UUID draft = createEvent(organizer, "Secret Draft", 40);

            mockMvc.perform(get("/events/{id}", published))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Rock Night"))
                    .andExpect(jsonPath("$.sections", hasSize(1)))
                    .andExpect(jsonPath("$.sections[0].priceCents").value(5000));

            mockMvc.perform(get("/events/{id}", draft)).andExpect(status().isNotFound());

            mockMvc.perform(get("/events"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].name").value("Rock Night"));
        }

        @Test
        @DisplayName("lists soonest first, with the cheapest seat price of each event")
        void orderingAndPrices() throws Exception {
            publishedEvent(organizer, "Later Show", 60, 9000);
            publishedEvent(organizer, "Sooner Show", 10, 2500);
            // A second, cheaper section on a third event: the listing shows the MINIMUM price.
            UUID twoSections = createEvent(organizer, "Middle Show", 30);
            addSectionRequest(organizer, twoSections, "VIP", 20000, 1, 5).andExpect(status().isCreated());
            addSectionRequest(organizer, twoSections, "Standing", 1500, 5, 5).andExpect(status().isCreated());
            publishRequest(organizer, twoSections).andExpect(status().isOk());

            mockMvc.perform(get("/events"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[*].name", contains("Sooner Show", "Middle Show", "Later Show")))
                    .andExpect(jsonPath("$.items[1].fromPriceCents").value(1500))
                    .andExpect(jsonPath("$.totalItems").value(3));
        }

        @Test
        @DisplayName("hides events that have already started, even if published")
        void hidesPastEvents() throws Exception {
            publishedEvent(organizer, "Upcoming", 10, 3000);
            jdbc.update("""
                    INSERT INTO events (id, organizer_id, name, venue, starts_at, status)
                    VALUES (gen_random_uuid(), ?, 'Past Gig', 'Arena', now() - interval '1 day', 'PUBLISHED')
                    """, organizer.id());

            mockMvc.perform(get("/events"))
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].name").value("Upcoming"));
        }

        @Test
        @DisplayName("paginates: page 1 of size 2 over 5 events")
        void pagination() throws Exception {
            for (int i = 1; i <= 5; i++) {
                publishedEvent(organizer, "Show " + i, 10 + i, 3000);
            }

            mockMvc.perform(get("/events").param("page", "1").param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[*].name", contains("Show 3", "Show 4")))
                    .andExpect(jsonPath("$.page").value(1))
                    .andExpect(jsonPath("$.size").value(2))
                    .andExpect(jsonPath("$.totalItems").value(5))
                    .andExpect(jsonPath("$.totalPages").value(3));

            // Past the last page: an empty list, not an error.
            mockMvc.perform(get("/events").param("page", "9").param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", empty()));
        }

        @ParameterizedTest(name = "page={0} size={1} is rejected")
        @CsvSource({"-1,20", "0,0", "0,101"})
        void invalidPaging(String page, String size) throws Exception {
            mockMvc.perform(get("/events").param("page", page).param("size", size))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("search is case-insensitive and finds fragments")
        void search() throws Exception {
            publishedEvent(organizer, "Rock Night", 10, 3000);
            publishedEvent(organizer, "Jazz Evening", 20, 3000);

            mockMvc.perform(get("/events").param("q", "ROCK"))
                    .andExpect(jsonPath("$.items[*].name", contains("Rock Night")));
            mockMvc.perform(get("/events").param("q", "ing"))
                    .andExpect(jsonPath("$.items[*].name", contains("Jazz Evening")));
            mockMvc.perform(get("/events").param("q", "   "))
                    .andExpect(jsonPath("$.items", hasSize(2)));
        }

        @Test
        @DisplayName("% and _ in a search are plain characters, not wildcards")
        void searchWildcardsAreEscaped() throws Exception {
            publishedEvent(organizer, "50% Off Gala", 10, 3000);
            publishedEvent(organizer, "50 Anything Goes", 20, 3000);
            publishedEvent(organizer, "Rock Night", 30, 3000);

            // If % were a wildcard, "50%" would match both "50..." events.
            mockMvc.perform(get("/events").param("q", "50%"))
                    .andExpect(jsonPath("$.items[*].name", contains("50% Off Gala")));
            // If _ were a wildcard, "_ock" would match "Rock".
            mockMvc.perform(get("/events").param("q", "_ock"))
                    .andExpect(jsonPath("$.items", empty()));
        }

        @Test
        @DisplayName("a search containing SQL is just text")
        void searchIsNotInjectable() throws Exception {
            publishedEvent(organizer, "Rock Night", 10, 3000);

            mockMvc.perform(get("/events").param("q", "'; DROP TABLE events; --"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", empty()));
            assertThat(count("SELECT count(*) FROM events")).isEqualTo(1);
        }

        @Test
        @DisplayName("a page of 25 events needs a fixed number of SQL statements, not 25+ (no N+1 queries)")
        void noNPlusOne() throws Exception {
            for (int i = 0; i < 25; i++) {
                publishedEvent(organizer, "Show " + i, 10 + i, 3000 + i);
            }
            Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
            statistics.clear();

            mockMvc.perform(get("/events").param("size", "25"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(25)));

            // The page itself, the total count for pagination, and ONE grouped query for all 25 prices.
            assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(3);
        }
    }
}
