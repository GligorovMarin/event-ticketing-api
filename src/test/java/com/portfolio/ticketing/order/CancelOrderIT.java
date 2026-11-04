package com.portfolio.ticketing.order;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** INTEGRATION test of cancelling orders: pending ones freely, paid ones (with a refund) until 48 hours before the event. */
@IntegrationTest
class CancelOrderIT {

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
    private OrderFixtures orderFixtures;
    @Autowired
    private SeatFixtures seatFixtures;
    @Autowired
    private TestClock clock;

    private Account organizer;
    private Account ana;
    private Account boris;
    private BuiltEvent event;
    private UUID floor;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        clock.freezeAt(T0);
        organizer = accounts.organizer("org@example.com");
        ana = accounts.customer("ana@example.com");
        boris = accounts.customer("boris@example.com");
        // The event is 30 days away: far outside the 48-hour window.
        event = eventFixtures.published(organizer, "Rock Night", 30, new SectionSpec("Floor", 5000, 4, 6));
        floor = event.section("Floor");
    }

    private ResultActions cancel(Account as, UUID orderId) throws Exception {
        return mockMvc.perform(post("/orders/{id}/cancel", orderId).header("Authorization", as.bearer()));
    }

    private UUID seat(String label) {
        return seatFixtures.seatId(floor, label);
    }

    private List<String> paymentStatuses(UUID orderId) {
        return jdbc.queryForList("SELECT status FROM payments WHERE order_id = ?", String.class, orderId);
    }

    /** Moves the event's start so it is exactly {@code fromNow} after the (frozen) current time. */
    private void startEventIn(Duration fromNow) {
        jdbc.update("UPDATE events SET starts_at = ? WHERE id = ?", Timestamp.from(T0.plus(fromNow)), event.eventId());
    }

    @Test
    @DisplayName("cancelling a PENDING order frees its seats at once; nothing to refund")
    void cancelPending() throws Exception {
        OrderResponse order = orderFixtures.place(ana, event.eventId(), seat("A1"), seat("A2"));

        cancel(ana, order.id())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").value("2026-12-01T12:00:00Z"));

        assertThat(seatFixtures.storedStatus(floor, "A1")).isEqualTo("AVAILABLE");
        assertThat(seatFixtures.orderOf(floor, "A1")).isNull();
        assertThat(paymentStatuses(order.id())).isEmpty();
        mockMvc.perform(get("/events/{id}/seats", event.eventId())).andExpect(jsonPath("$.summary.held").value(0));
    }

    @Test
    @DisplayName("the freed seats can immediately be ordered by someone else")
    void freedSeatsAreReusable() throws Exception {
        OrderResponse order = orderFixtures.place(ana, event.eventId(), seat("A1"));
        cancel(ana, order.id()).andExpect(status().isOk());

        orderFixtures.place(boris, event.eventId(), seat("A1"));

        assertThat(seatFixtures.storedStatus(floor, "A1")).isEqualTo("HELD");
    }

    @Test
    @DisplayName("after cancelling, the customer may create a new order for the same event")
    void canOrderAgain() throws Exception {
        OrderResponse order = orderFixtures.place(ana, event.eventId(), seat("A1"));
        cancel(ana, order.id()).andExpect(status().isOk());

        orderFixtures.place(ana, event.eventId(), seat("B1"));
    }

    @Test
    @DisplayName("cancelling a PAID order refunds the payment, frees the seats and marks the order CANCELLED")
    void cancelPaid() throws Exception {
        OrderResponse order = orderFixtures.placeAndPay(ana, event.eventId(), seat("A1"), seat("A2"));

        cancel(ana, order.id()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(paymentStatuses(order.id())).containsExactly("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id = ? AND refunded_at IS NOT NULL", Integer.class, order.id())).isEqualTo(1);
        assertThat(seatFixtures.storedStatus(floor, "A1")).isEqualTo("AVAILABLE");
        assertThat(seatFixtures.storedStatus(floor, "A2")).isEqualTo("AVAILABLE");
        mockMvc.perform(get("/events/{id}/seats", event.eventId())).andExpect(jsonPath("$.summary.sold").value(0));
    }

    @Test
    @DisplayName("the 48-hour window: allowed at exactly 48 hours before the event")
    void exactlyOnTheLimit() throws Exception {
        OrderResponse order = orderFixtures.placeAndPay(ana, event.eventId(), seat("A1"));
        startEventIn(Duration.ofHours(48));

        cancel(ana, order.id()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("...and refused one second later: 409, and NOTHING changes (still PAID, seat sold, no refund)")
    void oneSecondTooLate() throws Exception {
        OrderResponse order = orderFixtures.placeAndPay(ana, event.eventId(), seat("A1"));
        startEventIn(Duration.ofHours(48).minusSeconds(1));

        cancel(ana, order.id()).andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("48 hours")));

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order.id())).isEqualTo("PAID");
        assertThat(seatFixtures.storedStatus(floor, "A1")).isEqualTo("SOLD");
        assertThat(paymentStatuses(order.id())).containsExactly("APPROVED");
    }

    @Test
    @DisplayName("a PENDING order has no 48-hour rule: it can be cancelled even minutes before the event")
    void pendingHasNoWindow() throws Exception {
        OrderResponse order = orderFixtures.place(ana, event.eventId(), seat("A1"));
        startEventIn(Duration.ofHours(1));

        cancel(ana, order.id()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("if the REFUND fails the order stays PAID with the seat sold (503) and a retry succeeds when the provider is back")
    void refundFailure() throws Exception {
        OrderResponse order = orderFixtures.placeAndPayUnrefundable(ana, event.eventId(), seat("A1"));

        cancel(ana, order.id())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail", containsString("refund")));

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order.id())).isEqualTo("PAID");
        assertThat(seatFixtures.storedStatus(floor, "A1")).isEqualTo("SOLD");
        assertThat(paymentStatuses(order.id())).containsExactly("APPROVED");
    }

    @Test
    @DisplayName("cancelling twice is 409")
    void cancelTwice() throws Exception {
        OrderResponse order = orderFixtures.place(ana, event.eventId(), seat("A1"));
        cancel(ana, order.id()).andExpect(status().isOk());

        cancel(ana, order.id()).andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("already cancelled")));
    }

    @Test
    @DisplayName("an expired order cannot be cancelled (409): it is already over")
    void cancelExpired() throws Exception {
        OrderResponse order = orderFixtures.place(ana, event.eventId(), seat("A1"));
        clock.advance(Duration.ofMinutes(11));

        cancel(ana, order.id()).andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("expired")));
    }

    @Test
    @DisplayName("cancelling must not free a seat that has since been taken over by another customer's order")
    void doesNotReleaseSomeoneElsesSeat() throws Exception {
        // Ana's hold lapses and Boris takes the seat. Ana's order was never written down as expired.
        OrderResponse anasOrder = orderFixtures.place(ana, event.eventId(), seat("A1"));
        clock.advance(Duration.ofMinutes(11));
        OrderResponse borisOrder = orderFixtures.place(boris, event.eventId(), seat("A1"));

        // Ana's order is EXPIRED in effect; trying to cancel it is a 409, and Boris keeps his seat.
        cancel(ana, anasOrder.id()).andExpect(status().isConflict());

        assertThat(seatFixtures.orderOf(floor, "A1")).isEqualTo(borisOrder.id());
        assertThat(seatFixtures.storedStatus(floor, "A1")).isEqualTo("HELD");
    }

    @Test
    @DisplayName("someone else's order is 404, an unknown id 404, a malformed id 400; organizers 403; anonymous 401")
    void access() throws Exception {
        OrderResponse order = orderFixtures.place(ana, event.eventId(), seat("A1"));

        cancel(boris, order.id()).andExpect(status().isNotFound());
        cancel(ana, UUID.randomUUID()).andExpect(status().isNotFound());
        mockMvc.perform(post("/orders/not-a-uuid/cancel").header("Authorization", ana.bearer())).andExpect(status().isBadRequest());
        cancel(organizer, order.id()).andExpect(status().isForbidden());
        mockMvc.perform(post("/orders/{id}/cancel", order.id())).andExpect(status().isUnauthorized());

        assertThat(seatFixtures.storedStatus(floor, "A1")).isEqualTo("HELD");
    }

    @Test
    @DisplayName("the response of a paid order shows paidAt, and a cancelled one cancelledAt")
    void timestamps() throws Exception {
        OrderResponse order = orderFixtures.placeAndPay(ana, event.eventId(), seat("A1"));

        mockMvc.perform(get("/orders/{id}", order.id()).header("Authorization", ana.bearer()))
                .andExpect(jsonPath("$.paidAt").value("2026-12-01T12:00:00Z"))
                .andExpect(jsonPath("$.cancelledAt").doesNotExist());
        clock.advance(Duration.ofHours(1));
        cancel(ana, order.id()).andExpect(jsonPath("$.cancelledAt").value("2026-12-01T13:00:00Z"));
    }
}
