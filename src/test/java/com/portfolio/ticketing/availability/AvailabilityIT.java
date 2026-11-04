package com.portfolio.ticketing.availability;

import com.portfolio.ticketing.support.DatabaseCleaner;
import com.portfolio.ticketing.support.EventFixtures;
import com.portfolio.ticketing.support.EventFixtures.BuiltEvent;
import com.portfolio.ticketing.support.EventFixtures.SectionSpec;
import com.portfolio.ticketing.support.IntegrationTest;
import com.portfolio.ticketing.support.SeatFixtures;
import com.portfolio.ticketing.support.TestAccounts;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * INTEGRATION test of the seat map and the "find N seats together" endpoint, with seats put into specific states
 * in the real database. Both endpoints are public, so no token is sent.
 */
@IntegrationTest
class AvailabilityIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DatabaseCleaner cleaner;
    @Autowired
    private TestAccounts accounts;
    @Autowired
    private EventFixtures eventFixtures;
    @Autowired
    private SeatFixtures seatFixtures;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private TestAccounts.Account organizer;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        organizer = accounts.organizer("org@example.com");
    }

    @Nested
    @DisplayName("GET /events/{id}/seats")
    class SeatMap {

        @Test
        @DisplayName("shows sections, rows and seats in map order, with prices")
        void structure() throws Exception {
            BuiltEvent event = eventFixtures.published(organizer, "Rock Night", 30,
                    new SectionSpec("Orchestra", 8000, 2, 3),
                    new SectionSpec("Balcony", 3000, 1, 2));

            mockMvc.perform(get("/events/{id}/seats", event.eventId()))
                    .andExpect(status().isOk())
                    // sections ordered by name: Balcony, Orchestra
                    .andExpect(jsonPath("$.sections[*].name", contains("Balcony", "Orchestra")))
                    .andExpect(jsonPath("$.sections[1].priceCents").value(8000))
                    .andExpect(jsonPath("$.sections[1].rows[*].row", contains("A", "B")))
                    .andExpect(jsonPath("$.sections[1].rows[0].seats[*].number", contains(1, 2, 3)))
                    .andExpect(jsonPath("$.summary.total").value(8))
                    .andExpect(jsonPath("$.summary.available").value(8));
        }

        @Test
        @DisplayName("is public: no login needed")
        void isPublic() throws Exception {
            BuiltEvent event = eventFixtures.published(organizer, "Rock Night", 30, new SectionSpec("Floor", 5000, 1, 2));

            mockMvc.perform(get("/events/{id}/seats", event.eventId())).andExpect(status().isOk());
        }

        @Test
        @DisplayName("a draft event has no public seat map (404)")
        void draftIsHidden() throws Exception {
            BuiltEvent draft = eventFixtures.draft(organizer, "Secret", 30, new SectionSpec("Floor", 5000, 1, 2));

            mockMvc.perform(get("/events/{id}/seats", draft.eventId())).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("an unknown event is 404 and a malformed id is 400")
        void unknownIds() throws Exception {
            mockMvc.perform(get("/events/{id}/seats", UUID.randomUUID())).andExpect(status().isNotFound());
            mockMvc.perform(get("/events/not-a-uuid/seats")).andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("reflects sold and held seats, and counts them in the summary")
        void statusesAndSummary() throws Exception {
            BuiltEvent event = eventFixtures.published(organizer, "Rock Night", 30, new SectionSpec("Floor", 5000, 2, 5));
            UUID floor = event.section("Floor");
            seatFixtures.sell(floor, "A1", "A2");
            seatFixtures.hold(floor, Instant.now().plus(Duration.ofMinutes(10)), "B1");

            mockMvc.perform(get("/events/{id}/seats", event.eventId()))
                    .andExpect(jsonPath("$.summary.total").value(10))
                    .andExpect(jsonPath("$.summary.sold").value(2))
                    .andExpect(jsonPath("$.summary.held").value(1))
                    .andExpect(jsonPath("$.summary.available").value(7))
                    .andExpect(jsonPath("$.sections[0].rows[0].seats[0].status").value("SOLD"))
                    .andExpect(jsonPath("$.sections[0].rows[1].seats[0].status").value("HELD"))
                    .andExpect(jsonPath("$.sections[0].rows[1].seats[1].status").value("AVAILABLE"));
        }

        @Test
        @DisplayName("a hold that has run out is shown as AVAILABLE even though the row still says HELD")
        void lapsedHoldIsShownAsAvailable() throws Exception {
            BuiltEvent event = eventFixtures.published(organizer, "Rock Night", 30, new SectionSpec("Floor", 5000, 1, 4));
            UUID floor = event.section("Floor");
            seatFixtures.hold(floor, Instant.now().minus(Duration.ofMinutes(1)), "A1"); // lapsed
            seatFixtures.hold(floor, Instant.now().plus(Duration.ofMinutes(5)), "A2");   // still running

            mockMvc.perform(get("/events/{id}/seats", event.eventId()))
                    .andExpect(jsonPath("$.sections[0].rows[0].seats[0].status").value("AVAILABLE"))
                    .andExpect(jsonPath("$.sections[0].rows[0].seats[1].status").value("HELD"))
                    .andExpect(jsonPath("$.summary.available").value(3))
                    .andExpect(jsonPath("$.summary.held").value(1));
        }

        @Test
        @DisplayName("never exposes who holds or bought a seat")
        void exposesNoOrderDetails() throws Exception {
            BuiltEvent event = eventFixtures.published(organizer, "Rock Night", 30, new SectionSpec("Floor", 5000, 1, 2));
            seatFixtures.sell(event.section("Floor"), "A1");

            String body = mockMvc.perform(get("/events/{id}/seats", event.eventId()))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("orderId").doesNotContain("order_id").doesNotContain("holdExpiresAt");
        }

        @Test
        @DisplayName("a whole 1300-seat hall needs a fixed number of SQL statements (no N+1)")
        void fixedNumberOfStatements() throws Exception {
            BuiltEvent event = eventFixtures.published(organizer, "Stadium", 30, new SectionSpec("Stand", 2500, 26, 50));
            Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
            statistics.clear();

            mockMvc.perform(get("/events/{id}/seats", event.eventId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.summary.total").value(1300));

            // The event check, the sections, and all 1300 seats in one query. Not 1300 queries.
            assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(3);
        }
    }

    @Nested
    @DisplayName("GET /events/{id}/seats/suggestions")
    class Suggestions {

        private BuiltEvent event;
        private UUID floor;

        @BeforeEach
        void aSmallHall() {
            // One section: 3 rows of 8 seats, 5000 cents each.
            event = eventFixtures.published(organizer, "Rock Night", 30, new SectionSpec("Floor", 5000, 3, 8));
            floor = event.section("Floor");
        }

        private org.springframework.test.web.servlet.ResultActions suggest(int count) throws Exception {
            return mockMvc.perform(get("/events/{id}/seats/suggestions", event.eventId())
                    .param("sectionId", floor.toString())
                    .param("count", String.valueOf(count)));
        }

        @Test
        @DisplayName("an empty hall: the most central seats of the front row, with the total price")
        void emptyHall() throws Exception {
            suggest(4).andExpect(status().isOk())
                    .andExpect(jsonPath("$.requested").value(4))
                    // Row of 8, four seats: the middle is between 4 and 5, so seats 3,4,5,6.
                    .andExpect(jsonPath("$.seats[*].label", contains("A3", "A4", "A5", "A6")))
                    .andExpect(jsonPath("$.totalPriceCents").value(20000));
        }

        @Test
        @DisplayName("takes sold seats into account")
        void aroundSoldSeats() throws Exception {
            seatFixtures.sell(floor, "A4", "A5");

            // Row A now has 1-3 and 6-8 free; for 3 seats the candidates are 1-3 and 6-8, equally far from the middle: left wins.
            suggest(3).andExpect(jsonPath("$.seats[*].label", contains("A1", "A2", "A3")));
        }

        @Test
        @DisplayName("moves to the next row when the front row cannot fit the party")
        void nextRow() throws Exception {
            seatFixtures.sell(floor, "A2", "A4", "A6", "A8"); // row A is checkerboarded: no two free seats side by side

            suggest(2).andExpect(jsonPath("$.seats[*].label", contains("B4", "B5")));
        }

        @Test
        @DisplayName("seats whose hold has lapsed count as free; running holds do not")
        void lapsedHoldsAreFree() throws Exception {
            seatFixtures.hold(floor, Instant.now().minus(Duration.ofMinutes(1)), "A4"); // lapsed -> free
            seatFixtures.hold(floor, Instant.now().plus(Duration.ofMinutes(9)), "A5");   // running -> taken
            seatFixtures.sell(floor, "A3");

            // Free in row A: 1,2 and 4 (lapsed) and 6,7,8. For 3 seats only 6-8 works.
            suggest(3).andExpect(jsonPath("$.seats[*].label", contains("A6", "A7", "A8")));
        }

        @Test
        @DisplayName("no block anywhere: an empty answer, not an error")
        void nothingFits() throws Exception {
            // Every row has a taken seat in the middle, so no row has 8 free seats.
            seatFixtures.sell(floor, "A4", "B4", "C4");

            suggest(6).andExpect(status().isOk())
                    .andExpect(jsonPath("$.seats", empty()))
                    .andExpect(jsonPath("$.totalPriceCents").value(0));
        }

        @Test
        @DisplayName("a party of exactly 6 (the order limit) is allowed")
        void maximumParty() throws Exception {
            suggest(6).andExpect(status().isOk()).andExpect(jsonPath("$.seats", hasSize(6)));
        }

        @ParameterizedTest(name = "count={0} is rejected")
        @ValueSource(strings = {"0", "7", "-1", "abc"})
        void invalidCount(String count) throws Exception {
            mockMvc.perform(get("/events/{id}/seats/suggestions", event.eventId())
                            .param("sectionId", floor.toString()).param("count", count))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("the section is required")
        void sectionIsRequired() throws Exception {
            mockMvc.perform(get("/events/{id}/seats/suggestions", event.eventId()).param("count", "2"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a section of ANOTHER event is a 404, so ids cannot be probed across events")
        void foreignSection() throws Exception {
            BuiltEvent other = eventFixtures.published(organizer, "Jazz Night", 40, new SectionSpec("Stalls", 4000, 2, 4));

            mockMvc.perform(get("/events/{id}/seats/suggestions", event.eventId())
                            .param("sectionId", other.section("Stalls").toString()).param("count", "2"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("a draft event has no suggestions (404)")
        void draftEvent() throws Exception {
            BuiltEvent draft = eventFixtures.draft(organizer, "Secret", 30, new SectionSpec("Floor", 5000, 1, 4));

            mockMvc.perform(get("/events/{id}/seats/suggestions", draft.eventId())
                            .param("sectionId", draft.section("Floor").toString()).param("count", "2"))
                    .andExpect(status().isNotFound());
        }
    }
}
