package com.portfolio.ticketing.order;

import com.jayway.jsonpath.JsonPath;
import com.portfolio.ticketing.support.DatabaseCleaner;
import com.portfolio.ticketing.support.EventFixtures;
import com.portfolio.ticketing.support.EventFixtures.BuiltEvent;
import com.portfolio.ticketing.support.EventFixtures.SectionSpec;
import com.portfolio.ticketing.support.IntegrationTest;
import com.portfolio.ticketing.support.SeatFixtures;
import com.portfolio.ticketing.support.TestAccounts;
import com.portfolio.ticketing.support.TestAccounts.Account;
import com.portfolio.ticketing.support.TestClock;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * INTEGRATION test of creating (holding) and reading orders, against real PostgreSQL.
 * The "many customers at once" cases are in OrderConcurrencyIT.
 *
 * <p>Time is controlled with the {@link TestClock}: it is frozen at a known instant so hold deadlines can be
 * asserted to the second, and moved forward to expire holds without waiting.
 */
@IntegrationTest
class OrdersIT {

    private static final Instant T0 = Instant.parse("2026-12-01T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DatabaseCleaner cleaner;
    @Autowired
    private TestAccounts accounts;
    @Autowired
    private EventFixtures eventFixtures;
    @Autowired
    private SeatFixtures seatFixtures;
    @Autowired
    private TestClock clock;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Account organizer;
    private Account ana;
    private Account boris;
    private BuiltEvent event;
    private UUID floor;    // 3 rows x 6 seats, 5000 cents
    private UUID balcony;  // 2 rows x 4 seats, 2000 cents

    @BeforeEach
    void setUp() {
        cleaner.clean();
        clock.freezeAt(T0);
        organizer = accounts.organizer("org@example.com");
        ana = accounts.customer("ana@example.com");
        boris = accounts.customer("boris@example.com");
        event = eventFixtures.published(organizer, "Rock Night", 30,
                new SectionSpec("Floor", 5000, 3, 6),
                new SectionSpec("Balcony", 2000, 2, 4));
        floor = event.section("Floor");
        balcony = event.section("Balcony");
    }

    // ---- helpers -------------------------------------------------------------------------------

    private ResultActions placeOrder(Account as, UUID eventId, UUID... seatIds) throws Exception {
        String ids = List.of(seatIds).stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
        return mockMvc.perform(post("/orders")
                .header("Authorization", as.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":\"%s\",\"seatIds\":[%s]}".formatted(eventId, ids)));
    }

    private UUID seat(UUID section, String label) {
        return seatFixtures.seatId(section, label);
    }

    private UUID orderIdOf(ResultActions result) throws Exception {
        return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id"));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    // ============================================================================================
    @Nested
    @DisplayName("holding seats")
    class Holding {

        @Test
        @DisplayName("creates a PENDING order, prices it on the server, and holds the seats for 10 minutes")
        void createsOrder() throws Exception {
            placeOrder(ana, event.eventId(), seat(floor, "A3"), seat(floor, "A4"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.eventName").value("Rock Night"))
                    .andExpect(jsonPath("$.totalCents").value(10000))
                    // frozen clock 12:00:00 + 10 minutes
                    .andExpect(jsonPath("$.holdExpiresAt").value("2026-12-01T12:10:00Z"))
                    .andExpect(jsonPath("$.items", hasSize(2)))
                    .andExpect(jsonPath("$.items[*].label", contains("A3", "A4")))
                    .andExpect(jsonPath("$.items[0].section").value("Floor"))
                    .andExpect(jsonPath("$.items[0].priceCents").value(5000));
        }

        @Test
        @DisplayName("the seats really are HELD in the database, linked to the order and expiring at the same moment")
        void seatsAreHeld() throws Exception {
            UUID orderId = orderIdOf(placeOrder(ana, event.eventId(), seat(floor, "B1")));

            assertThat(seatFixtures.storedStatus(floor, "B1")).isEqualTo("HELD");
            assertThat(seatFixtures.orderOf(floor, "B1")).isEqualTo(orderId);
            assertThat(count("SELECT count(*) FROM seats WHERE order_id = ? AND hold_expires_at = '2026-12-01 12:10:00+00'", orderId)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM order_items WHERE order_id = ?", orderId)).isEqualTo(1);
        }

        @Test
        @DisplayName("prices each seat by ITS section: 2 floor seats + 1 balcony seat = 12000")
        void mixedSections() throws Exception {
            placeOrder(ana, event.eventId(), seat(floor, "A1"), seat(floor, "A2"), seat(balcony, "A1"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.totalCents").value(12000))
                    // seat-map order: sections by name, so Balcony first
                    .andExpect(jsonPath("$.items[*].section", contains("Balcony", "Floor", "Floor")));
        }

        @Test
        @DisplayName("the held seat shows as HELD on the public seat map")
        void visibleOnTheSeatMap() throws Exception {
            placeOrder(ana, event.eventId(), seat(floor, "A1")).andExpect(status().isCreated());

            mockMvc.perform(get("/events/{id}/seats", event.eventId()))
                    .andExpect(jsonPath("$.summary.held").value(1));
        }

        @Test
        @DisplayName("the maximum of 6 seats is accepted")
        void sixSeats() throws Exception {
            placeOrder(ana, event.eventId(), seat(floor, "A1"), seat(floor, "A2"), seat(floor, "A3"),
                    seat(floor, "A4"), seat(floor, "A5"), seat(floor, "A6"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.totalCents").value(30000));
        }
    }

    // ============================================================================================
    @Nested
    @DisplayName("rejected requests change nothing")
    class Rejections {

        private void assertNothingWasHeld() {
            assertThat(count("SELECT count(*) FROM orders")).isZero();
            assertThat(count("SELECT count(*) FROM order_items")).isZero();
            assertThat(count("SELECT count(*) FROM seats WHERE status <> 'AVAILABLE'")).isZero();
        }

        @Test
        @DisplayName("an anonymous caller gets 401, an organizer 403")
        void whoMayOrder() throws Exception {
            mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"eventId\":\"%s\",\"seatIds\":[\"%s\"]}".formatted(event.eventId(), seat(floor, "A1"))))
                    .andExpect(status().isUnauthorized());
            placeOrder(organizer, event.eventId(), seat(floor, "A1")).andExpect(status().isForbidden());

            assertNothingWasHeld();
        }

        @Test
        @DisplayName("invalid bodies are 400: no seats, 7 seats, the same seat twice, a missing event, an unknown field")
        void invalidBodies() throws Exception {
            String eventId = event.eventId().toString();
            String seatA1 = seat(floor, "A1").toString();

            mockMvc.perform(post("/orders").header("Authorization", ana.bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"eventId\":\"%s\",\"seatIds\":[]}".formatted(eventId))).andExpect(status().isBadRequest());
            mockMvc.perform(post("/orders").header("Authorization", ana.bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"seatIds\":[\"%s\"]}".formatted(seatA1))).andExpect(status().isBadRequest());
            mockMvc.perform(post("/orders").header("Authorization", ana.bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"eventId\":\"%s\",\"seatIds\":[\"%s\",\"%s\"]}".formatted(eventId, seatA1, seatA1))).andExpect(status().isBadRequest());
            mockMvc.perform(post("/orders").header("Authorization", ana.bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"eventId\":\"%s\",\"seatIds\":[\"%s\"],\"totalCents\":1}".formatted(eventId, seatA1))).andExpect(status().isBadRequest());

            // Seven different seats.
            String seven = List.of("A1", "A2", "A3", "A4", "A5", "A6", "B1").stream()
                    .map(label -> "\"" + seat(floor, label) + "\"").collect(Collectors.joining(","));
            mockMvc.perform(post("/orders").header("Authorization", ana.bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"eventId\":\"%s\",\"seatIds\":[%s]}".formatted(eventId, seven))).andExpect(status().isBadRequest());

            assertNothingWasHeld();
        }

        @Test
        @DisplayName("a seat that does not exist, or belongs to ANOTHER event, is 404 and nothing at all is held")
        void unknownOrForeignSeat() throws Exception {
            BuiltEvent other = eventFixtures.published(organizer, "Jazz Night", 40, new SectionSpec("Stalls", 4000, 1, 4));

            placeOrder(ana, event.eventId(), seat(floor, "A1"), UUID.randomUUID()).andExpect(status().isNotFound());
            // A perfectly real seat, but of a different event: the valid seat A1 must not be held either.
            placeOrder(ana, event.eventId(), seat(floor, "A1"), seat(other.section("Stalls"), "A1")).andExpect(status().isNotFound());

            assertNothingWasHeld();
        }

        @Test
        @DisplayName("an unknown event is 404, a draft event is 404")
        void eventNotOnSale() throws Exception {
            BuiltEvent draft = eventFixtures.draft(organizer, "Secret", 30, new SectionSpec("Floor", 5000, 1, 2));

            placeOrder(ana, UUID.randomUUID(), seat(floor, "A1")).andExpect(status().isNotFound());
            placeOrder(ana, draft.eventId(), seat(draft.section("Floor"), "A1")).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("an event that has already started cannot be ordered (409)")
        void eventAlreadyStarted() throws Exception {
            jdbc.update("UPDATE events SET starts_at = ? WHERE id = ?", java.sql.Timestamp.from(T0.minusSeconds(60)), event.eventId());

            placeOrder(ana, event.eventId(), seat(floor, "A1")).andExpect(status().isConflict());

            assertNothingWasHeld();
        }

        @Test
        @DisplayName("a SOLD seat is 409, the message names it, and the OTHER requested seats are not held (all or nothing)")
        void soldSeatIsAllOrNothing() throws Exception {
            seatFixtures.sell(floor, "A2");

            placeOrder(ana, event.eventId(), seat(floor, "A1"), seat(floor, "A2"), seat(floor, "A3"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail", containsString("A2")));

            assertThat(seatFixtures.storedStatus(floor, "A1")).isEqualTo("AVAILABLE");
            assertThat(seatFixtures.storedStatus(floor, "A3")).isEqualTo("AVAILABLE");
            assertThat(count("SELECT count(*) FROM orders WHERE customer_id = ?", ana.id())).isZero();
        }

        @Test
        @DisplayName("every unavailable seat is listed in one message")
        void allTakenSeatsAreListed() throws Exception {
            seatFixtures.sell(floor, "A1");
            seatFixtures.hold(floor, T0.plus(Duration.ofMinutes(5)), "A2");

            placeOrder(ana, event.eventId(), seat(floor, "A1"), seat(floor, "A2"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail", containsString("A1")))
                    .andExpect(jsonPath("$.detail", containsString("A2")));
        }

        @Test
        @DisplayName("a seat held by someone else whose hold is still running is 409")
        void activeHoldBySomeoneElse() throws Exception {
            seatFixtures.hold(floor, T0.plus(Duration.ofMinutes(3)), "A1");

            placeOrder(ana, event.eventId(), seat(floor, "A1")).andExpect(status().isConflict());
        }
    }

    // ============================================================================================
    @Nested
    @DisplayName("holds that run out (lazy expiry)")
    class Expiry {

        @Test
        @DisplayName("a seat whose hold lapsed can be taken straight away, and moves to the new order")
        void lapsedHoldCanBeTaken() throws Exception {
            seatFixtures.hold(floor, T0.minus(Duration.ofMinutes(1)), "A1");
            UUID previousOrder = seatFixtures.orderOf(floor, "A1");

            UUID newOrder = orderIdOf(placeOrder(ana, event.eventId(), seat(floor, "A1")).andExpect(status().isCreated()));

            assertThat(seatFixtures.orderOf(floor, "A1")).isEqualTo(newOrder).isNotEqualTo(previousOrder);
            assertThat(seatFixtures.storedStatus(floor, "A1")).isEqualTo("HELD");
        }

        @Test
        @DisplayName("a hold ending exactly now is over: the seat can be taken")
        void holdEndingNow() throws Exception {
            seatFixtures.hold(floor, T0, "A1");

            placeOrder(ana, event.eventId(), seat(floor, "A1")).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("ten minutes later the order shows as EXPIRED, with no cleanup job involved")
        void orderExpiresByItself() throws Exception {
            UUID orderId = orderIdOf(placeOrder(ana, event.eventId(), seat(floor, "A1")));
            clock.advance(Duration.ofMinutes(9).plusSeconds(59));

            mockMvc.perform(get("/orders/{id}", orderId).header("Authorization", ana.bearer()))
                    .andExpect(jsonPath("$.status").value("PENDING"));

            clock.advance(Duration.ofSeconds(1));
            mockMvc.perform(get("/orders/{id}", orderId).header("Authorization", ana.bearer()))
                    .andExpect(jsonPath("$.status").value("EXPIRED"));
        }

        @Test
        @DisplayName("after the hold ran out, somebody else can take the seats Ana was holding")
        void seatsGoToSomeoneElse() throws Exception {
            placeOrder(ana, event.eventId(), seat(floor, "A1")).andExpect(status().isCreated());
            placeOrder(boris, event.eventId(), seat(floor, "A1")).andExpect(status().isConflict());

            clock.advance(Duration.ofMinutes(10));

            placeOrder(boris, event.eventId(), seat(floor, "A1")).andExpect(status().isCreated());
        }
    }

    // ============================================================================================
    @Nested
    @DisplayName("one order in progress per customer and event")
    class OneAtATime {

        @Test
        @DisplayName("a second order while the first is pending is 409, and holds nothing")
        void secondOrderIsRefused() throws Exception {
            placeOrder(ana, event.eventId(), seat(floor, "A1")).andExpect(status().isCreated());

            placeOrder(ana, event.eventId(), seat(floor, "B1"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail", containsString("in progress")));

            assertThat(seatFixtures.storedStatus(floor, "B1")).isEqualTo("AVAILABLE");
            assertThat(count("SELECT count(*) FROM orders WHERE customer_id = ?", ana.id())).isEqualTo(1);
        }

        @Test
        @DisplayName("once the first order has expired, a new one is allowed")
        void allowedAfterExpiry() throws Exception {
            UUID first = orderIdOf(placeOrder(ana, event.eventId(), seat(floor, "A1")));
            clock.advance(Duration.ofMinutes(11));

            UUID second = orderIdOf(placeOrder(ana, event.eventId(), seat(floor, "B1")).andExpect(status().isCreated()));

            assertThat(second).isNotEqualTo(first);
            assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, first)).isEqualTo("EXPIRED");
        }

        @Test
        @DisplayName("the old order of a customer can be re-ordered for the same seats after it expired")
        void sameSeatsAgain() throws Exception {
            placeOrder(ana, event.eventId(), seat(floor, "A1"), seat(floor, "A2")).andExpect(status().isCreated());
            clock.advance(Duration.ofMinutes(11));

            placeOrder(ana, event.eventId(), seat(floor, "A1"), seat(floor, "A2")).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("different customers each get their own order, and one customer may order for different events")
        void independentOrders() throws Exception {
            BuiltEvent other = eventFixtures.published(organizer, "Jazz Night", 40, new SectionSpec("Stalls", 4000, 1, 4));

            placeOrder(ana, event.eventId(), seat(floor, "A1")).andExpect(status().isCreated());
            placeOrder(boris, event.eventId(), seat(floor, "A2")).andExpect(status().isCreated());
            placeOrder(ana, other.eventId(), seat(other.section("Stalls"), "A1")).andExpect(status().isCreated());
        }
    }

    // ============================================================================================
    @Nested
    @DisplayName("reading orders")
    class Reading {

        @Test
        @DisplayName("a customer sees their own orders, newest first, and never someone else's")
        void listMine() throws Exception {
            UUID first = orderIdOf(placeOrder(ana, event.eventId(), seat(floor, "A1")));
            clock.advance(Duration.ofMinutes(11)); // so Ana may order again
            BuiltEvent other = eventFixtures.published(organizer, "Jazz Night", 40, new SectionSpec("Stalls", 4000, 1, 4));
            UUID second = orderIdOf(placeOrder(ana, other.eventId(), seat(other.section("Stalls"), "A1")));
            placeOrder(boris, event.eventId(), seat(floor, "A2")).andExpect(status().isCreated());

            mockMvc.perform(get("/orders").header("Authorization", ana.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].id").value(second.toString()))
                    .andExpect(jsonPath("$[1].id").value(first.toString()))
                    .andExpect(jsonPath("$[1].status").value("EXPIRED"));
        }

        @Test
        @DisplayName("someone else's order is 404 (not 403), an unknown id is 404, a malformed id is 400")
        void ownershipAndIds() throws Exception {
            UUID anasOrder = orderIdOf(placeOrder(ana, event.eventId(), seat(floor, "A1")));

            mockMvc.perform(get("/orders/{id}", anasOrder).header("Authorization", boris.bearer())).andExpect(status().isNotFound());
            mockMvc.perform(get("/orders/{id}", UUID.randomUUID()).header("Authorization", ana.bearer())).andExpect(status().isNotFound());
            mockMvc.perform(get("/orders/not-a-uuid").header("Authorization", ana.bearer())).andExpect(status().isBadRequest());
            mockMvc.perform(get("/orders/{id}", anasOrder).header("Authorization", ana.bearer())).andExpect(status().isOk());
        }

        @Test
        @DisplayName("organizers and anonymous callers cannot read orders")
        void whoMayRead() throws Exception {
            mockMvc.perform(get("/orders").header("Authorization", organizer.bearer())).andExpect(status().isForbidden());
            mockMvc.perform(get("/orders")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("listing 6 orders across 6 events needs a fixed number of SQL statements (no N+1)")
        void noNPlusOne() throws Exception {
            for (int i = 0; i < 6; i++) {
                BuiltEvent each = eventFixtures.published(organizer, "Show " + i, 30 + i, new SectionSpec("Floor", 3000, 1, 4));
                placeOrder(ana, each.eventId(), seat(each.section("Floor"), "A1"), seat(each.section("Floor"), "A2")).andExpect(status().isCreated());
            }
            Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
            statistics.clear();

            mockMvc.perform(get("/orders").header("Authorization", ana.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(6)))
                    .andExpect(jsonPath("$[0].items", hasSize(2)));

            // orders, items, seats, sections, events: five queries, however many orders.
            assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(5);
        }
    }

    // ============================================================================================
    @Nested
    @DisplayName("the database enforces the rules too (no application code involved)")
    class DatabaseRules {

        private void insertOrder(UUID customerId, String status) {
            jdbc.update("""
                    INSERT INTO orders (id, customer_id, event_id, status, total_cents, hold_expires_at)
                    VALUES (gen_random_uuid(), ?, ?, ?, 0, now() + interval '10 minutes')
                    """, customerId, event.eventId(), status);
        }

        @Test
        @DisplayName("a customer cannot have two PENDING orders for the same event (partial unique index)")
        void onePendingOrder() {
            insertOrder(ana.id(), "PENDING");

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> insertOrder(ana.id(), "PENDING"))
                    .hasMessageContaining("orders_one_pending_per_customer_event");
        }

        @Test
        @DisplayName("...but any number of finished orders is fine, and another customer may have a pending one")
        void finishedOrdersAreUnlimited() {
            insertOrder(ana.id(), "PENDING");
            insertOrder(ana.id(), "PAID");
            insertOrder(ana.id(), "CANCELLED");
            insertOrder(ana.id(), "EXPIRED");
            insertOrder(boris.id(), "PENDING");

            assertThat(count("SELECT count(*) FROM orders")).isEqualTo(5);
        }

        @Test
        @DisplayName("a seat cannot point at an order that does not exist (foreign key)")
        void seatNeedsARealOrder() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                            "UPDATE seats SET status = 'SOLD', order_id = gen_random_uuid() WHERE id = ?", seat(floor, "A1")))
                    .hasMessageContaining("seats_order_fk");
        }

        @Test
        @DisplayName("an unknown order status and a negative total are refused (CHECK constraints)")
        void checkConstraints() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> insertOrder(ana.id(), "REFUNDED"))
                    .hasMessageContaining("orders_status_check");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                            INSERT INTO orders (id, customer_id, event_id, status, total_cents, hold_expires_at)
                            VALUES (gen_random_uuid(), ?, ?, 'PAID', -5, now())
                            """, ana.id(), event.eventId()))
                    .hasMessageContaining("orders_total_cents_check");
        }
    }
}
