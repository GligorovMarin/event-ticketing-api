package com.portfolio.ticketing.report;

import com.portfolio.ticketing.order.dto.OrderResponse;
import com.portfolio.ticketing.support.DatabaseCleaner;
import com.portfolio.ticketing.support.EventFixtures;
import com.portfolio.ticketing.support.EventFixtures.BuiltEvent;
import com.portfolio.ticketing.support.EventFixtures.SectionSpec;
import com.portfolio.ticketing.support.IntegrationTest;
import com.portfolio.ticketing.support.OrderFixtures;
import com.portfolio.ticketing.support.SeatFixtures;
import com.portfolio.ticketing.support.TestAccounts;
import com.portfolio.ticketing.support.TestAccounts.Account;
import com.portfolio.ticketing.support.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * INTEGRATION test of the organizer's sales report. The story it checks: one event, a few customers doing different
 * things (paying, cancelling after paying, holding, letting a hold lapse), and a report whose every number can be
 * worked out by hand.
 */
@IntegrationTest
class SalesReportIT {

    private static final Instant T0 = Instant.parse("2026-12-01T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DatabaseCleaner cleaner;
    @Autowired
    private TestAccounts accounts;
    @Autowired
    private EventFixtures eventFixtures;
    @Autowired
    private OrderFixtures orderFixtures;
    @Autowired
    private SeatFixtures seatFixtures;
    @Autowired
    private TestClock clock;

    private Account organizer;
    private BuiltEvent event;
    private UUID floor;     // 2 rows x 5 = 10 seats at 5000
    private UUID balcony;   // 1 row x 4 = 4 seats at 2000

    @BeforeEach
    void setUp() {
        cleaner.clean();
        clock.freezeAt(T0);
        organizer = accounts.organizer("org@example.com");
        event = eventFixtures.published(organizer, "Rock Night", 30,
                new SectionSpec("Floor", 5000, 2, 5),
                new SectionSpec("Balcony", 2000, 1, 4));
        floor = event.section("Floor");
        balcony = event.section("Balcony");
    }

    private UUID seat(UUID section, String label) {
        return seatFixtures.seatId(section, label);
    }

    @Test
    @DisplayName("an event nobody has bought from: everything available, zero revenue, sections still listed")
    void emptyEvent() throws Exception {
        mockMvc.perform(get("/organizer/events/{id}/sales", event.eventId()).header("Authorization", organizer.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventName").value("Rock Night"))
                .andExpect(jsonPath("$.capacity").value(14))
                .andExpect(jsonPath("$.sold").value(0))
                .andExpect(jsonPath("$.held").value(0))
                .andExpect(jsonPath("$.available").value(14))
                .andExpect(jsonPath("$.revenueCents").value(0))
                .andExpect(jsonPath("$.orders.paid").value(0))
                // LEFT JOINs: sections with no sales still appear.
                .andExpect(jsonPath("$.sections[*].name", contains("Balcony", "Floor")))
                .andExpect(jsonPath("$.sections[0].soldPercent").value(0.0));
    }

    @Test
    @DisplayName("every number adds up after paid, cancelled-after-paid, pending and lapsed orders")
    void theWholeStory() throws Exception {
        Account ana = accounts.customer("ana@example.com");
        Account boris = accounts.customer("boris@example.com");
        Account clara = accounts.customer("clara@example.com");
        Account dino = accounts.customer("dino@example.com");

        // Ana pays for 3 floor seats: 15000.
        orderFixtures.placeAndPay(ana, event.eventId(), seat(floor, "A1"), seat(floor, "A2"), seat(floor, "A3"));
        // Boris pays for 1 balcony seat (2000) and then cancels: refunded, so it must NOT count.
        OrderResponse borisOrder = orderFixtures.placeAndPay(boris, event.eventId(), seat(balcony, "A1"));
        mockMvc.perform(post("/orders/{id}/cancel", borisOrder.id()).header("Authorization", boris.bearer())).andExpect(status().isOk());
        // Dino holds 1 floor seat, and his hold lapses.
        orderFixtures.place(dino, event.eventId(), seat(floor, "B5"));
        clock.advance(Duration.ofMinutes(11));
        // Clara holds 2 floor seats right now.
        orderFixtures.place(clara, event.eventId(), seat(floor, "B1"), seat(floor, "B2"));

        mockMvc.perform(get("/organizer/events/{id}/sales", event.eventId()).header("Authorization", organizer.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(14))
                .andExpect(jsonPath("$.sold").value(3))
                .andExpect(jsonPath("$.held").value(2))          // Clara's 2; Dino's lapsed seat is not counted
                .andExpect(jsonPath("$.available").value(9))     // 14 - 3 - 2
                .andExpect(jsonPath("$.revenueCents").value(15000))
                .andExpect(jsonPath("$.orders.paid").value(1))
                .andExpect(jsonPath("$.orders.pending").value(1))
                .andExpect(jsonPath("$.orders.cancelled").value(1))
                .andExpect(jsonPath("$.orders.expired").value(1)) // Dino's: lazy expiry applied in SQL
                .andExpect(jsonPath("$.sections[0].name").value("Balcony"))
                .andExpect(jsonPath("$.sections[0].sold").value(0))
                .andExpect(jsonPath("$.sections[0].revenueCents").value(0))
                .andExpect(jsonPath("$.sections[1].name").value("Floor"))
                .andExpect(jsonPath("$.sections[1].capacity").value(10))
                .andExpect(jsonPath("$.sections[1].sold").value(3))
                .andExpect(jsonPath("$.sections[1].revenueCents").value(15000))
                .andExpect(jsonPath("$.sections[1].soldPercent").value(30.0));
    }

    @Test
    @DisplayName("sold percentage is rounded to one decimal: 1 of 3 seats is 33.3")
    void percentageRounding() throws Exception {
        BuiltEvent small = eventFixtures.published(organizer, "Tiny Show", 40, new SectionSpec("Stalls", 1000, 1, 3));
        orderFixtures.placeAndPay(accounts.customer("ana@example.com"), small.eventId(), seat(small.section("Stalls"), "A1"));

        mockMvc.perform(get("/organizer/events/{id}/sales", small.eventId()).header("Authorization", organizer.bearer()))
                .andExpect(jsonPath("$.sections[0].soldPercent").value(33.3));
    }

    @Test
    @DisplayName("revenue uses the price paid at order time, not today's section price")
    void usesThePriceAtOrderTime() throws Exception {
        orderFixtures.placeAndPay(accounts.customer("ana@example.com"), event.eventId(), seat(floor, "A1"));
        // The section's price changes afterwards (an organizer correcting it, a script ...).
        mockMvcUpdatePrice();

        mockMvc.perform(get("/organizer/events/{id}/sales", event.eventId()).header("Authorization", organizer.bearer()))
                .andExpect(jsonPath("$.revenueCents").value(5000));
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private void mockMvcUpdatePrice() {
        jdbc.update("UPDATE sections SET price_cents = 99999 WHERE id = ?", floor);
    }

    @Test
    @DisplayName("another organizer's event is 404; customers 403; anonymous 401; unknown id 404; malformed id 400")
    void access() throws Exception {
        Account other = accounts.organizer("other-org@example.com");

        mockMvc.perform(get("/organizer/events/{id}/sales", event.eventId()).header("Authorization", other.bearer())).andExpect(status().isNotFound());
        mockMvc.perform(get("/organizer/events/{id}/sales", event.eventId()).header("Authorization", accounts.customer("ana@example.com").bearer())).andExpect(status().isForbidden());
        mockMvc.perform(get("/organizer/events/{id}/sales", event.eventId())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/organizer/events/{id}/sales", UUID.randomUUID()).header("Authorization", organizer.bearer())).andExpect(status().isNotFound());
        mockMvc.perform(get("/organizer/events/not-a-uuid/sales").header("Authorization", organizer.bearer())).andExpect(status().isBadRequest());
    }
}
