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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * INTEGRATION test of paying for an order: the happy path, every way a payment can fail, double clicks, and the race
 * between "pay" and "cancel". The fake payment provider (payment/FakePaymentGateway) is driven by the token.
 */
@IntegrationTest
class PaymentIT {

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
        event = eventFixtures.published(organizer, "Rock Night", 30, new SectionSpec("Floor", 5000, 4, 6));
        floor = event.section("Floor");
    }

    private ResultActions pay(Account as, UUID orderId, String token) throws Exception {
        return mockMvc.perform(post("/orders/{id}/pay", orderId)
                .header("Authorization", as.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentToken\":\"%s\"}".formatted(token)));
    }

    private UUID seat(String label) {
        return seatFixtures.seatId(floor, label);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private List<String> paymentStatuses(UUID orderId) {
        return jdbc.queryForList("SELECT status FROM payments WHERE order_id = ? ORDER BY created_at, status", String.class, orderId);
    }

    // ============================================================================================
    @Nested
    @DisplayName("a successful payment")
    class Success {

        @Test
        @DisplayName("turns the order PAID and the held seats SOLD, and records the payment")
        void paysTheOrder() throws Exception {
            OrderResponse order = orderFixtures.place(ana, event.eventId(), seat("A1"), seat("A2"));

            pay(ana, order.id(), "tok_ok")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PAID"))
                    .andExpect(jsonPath("$.paidAt").value("2026-12-01T12:00:00Z"))
                    .andExpect(jsonPath("$.totalCents").value(10000));

            assertThat(seatFixtures.storedStatus(floor, "A1")).isEqualTo("SOLD");
            assertThat(seatFixtures.storedStatus(floor, "A2")).isEqualTo("SOLD");
            assertThat(seatFixtures.orderOf(floor, "A1")).isEqualTo(order.id());
            // A sold seat no longer has a hold deadline (CHECK constraint seats_hold_consistency_check).
            assertThat(count("SELECT count(*) FROM seats WHERE order_id = ? AND hold_expires_at IS NULL", order.id())).isEqualTo(2);

            assertThat(paymentStatuses(order.id())).containsExactly("APPROVED");
            assertThat(count("SELECT count(*) FROM payments WHERE order_id = ? AND amount_cents = 10000 AND gateway_reference LIKE 'pay_%'", order.id())).isEqualTo(1);
        }

        @Test
        @DisplayName("the sold seats show as SOLD on the public map, and can never be ordered again")
        void soldForGood() throws Exception {
            OrderResponse order = orderFixtures.place(ana, event.eventId(), seat("A1"));
            pay(ana, order.id(), "tok_ok").andExpect(status().isOk());

            mockMvc.perform(get("/events/{id}/seats", event.eventId()))
                    .andExpect(jsonPath("$.summary.sold").value(1))
                    .andExpect(jsonPath("$.summary.held").value(0));

            // Even a day after the original hold would have ended, a SOLD seat stays sold.
            clock.advance(Duration.ofDays(1));
            mockMvc.perform(post("/orders").header("Authorization", boris.bearer()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"eventId\":\"%s\",\"seatIds\":[\"%s\"]}".formatted(event.eventId(), seat("A1"))))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("paying an already PAID order returns it and does NOT charge again (idempotent)")
        void payingTwice() throws Exception {
            OrderResponse order = orderFixtures.place(ana, event.eventId(), seat("A1"));
            pay(ana, order.id(), "tok_ok").andExpect(status().isOk());

            pay(ana, order.id(), "tok_ok").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));

            assertThat(paymentStatuses(order.id())).containsExactly("APPROVED");
        }

        @Test
        @DisplayName("the customer can pay at the very last moment: one millisecond before the hold ends")
        void lastMillisecond() throws Exception {
            OrderResponse order = orderFixtures.place(ana, event.eventId(), seat("A1"));
            clock.freezeAt(T0.plus(Duration.ofMinutes(10)).minusMillis(1));

            pay(ana, order.id(), "tok_ok").andExpect(status().isOk());
        }
    }

    // ============================================================================================
    @Nested
    @DisplayName("a payment that fails")
    class Failure {

        @Test
        @DisplayName("a declined card is 402, is recorded as DECLINED, and leaves the order open with the seats still held")
        void declined() throws Exception {
            OrderResponse order = orderFixtures.place(ana, event.eventId(), seat("A1"));

            pay(ana, order.id(), "tok_declined")
                    .andExpect(status().isPaymentRequired())
                    .andExpect(jsonPath("$.detail", containsString("declined")));

            assertThat(paymentStatuses(order.id())).containsExactly("DECLINED");   // the attempt was kept
            assertThat(seatFixtures.storedStatus(floor, "A1")).isEqualTo("HELD");
            mockMvc.perform(get("/orders/{id}", order.id()).header("Authorization", ana.bearer()))
                    .andExpect(jsonPath("$.status").value("PENDING"));
        }

        @Test
        @DisplayName("after a decline the customer can try another card and succeed: one DECLINED then one APPROVED payment")
        void retryAfterDecline() throws Exception {
            OrderResponse order = orderFixtures.place(ana, event.eventId(), seat("A1"));
            pay(ana, order.id(), "tok_declined").andExpect(status().isPaymentRequired());

            pay(ana, order.id(), "tok_ok").andExpect(status().isOk());

            assertThat(paymentStatuses(order.id())).containsExactlyInAnyOrder("DECLINED", "APPROVED");
        }

        @Test
        @DisplayName("when the provider is down the answer is 503 and NOTHING changes: no payment row, seats still held")
        void providerDown() throws Exception {
            OrderResponse order = orderFixtures.place(ana, event.eventId(), seat("A1"));

            pay(ana, order.id(), "tok_timeout")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.detail", containsString("not been charged")));

            assertThat(paymentStatuses(order.id())).isEmpty();
            assertThat(seatFixtures.storedStatus(floor, "A1")).isEqualTo("HELD");
            // ...and paying again works once the provider is back.
            pay(ana, order.id(), "tok_ok").andExpect(status().isOk());
        }

        @Test
        @DisplayName("an unknown token is 400 and a blank one too; no payment is recorded")
        void badTokens() throws Exception {
            OrderResponse order = orderFixtures.place(ana, event.eventId(), seat("A1"));

            pay(ana, order.id(), "tok_nonsense").andExpect(status().isBadRequest());
            pay(ana, order.id(), "   ").andExpect(status().isBadRequest());

            assertThat(count("SELECT count(*) FROM payments")).isZero();
        }

        @Test
        @DisplayName("once the hold has ended the order cannot be paid (409), at the exact second it ends")
        void expiredHold() throws Exception {
            OrderResponse order = orderFixtures.place(ana, event.eventId(), seat("A1"));
            clock.freezeAt(T0.plus(Duration.ofMinutes(10))); // the very instant the hold ends

            pay(ana, order.id(), "tok_ok")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail", containsString("expired")));

            assertThat(count("SELECT count(*) FROM payments")).isZero();
            assertThat(count("SELECT count(*) FROM seats WHERE status = 'SOLD'")).isZero();
        }

        @Test
        @DisplayName("a cancelled order cannot be paid (409)")
        void cancelledOrder() throws Exception {
            OrderResponse order = orderFixtures.place(ana, event.eventId(), seat("A1"));
            mockMvc.perform(post("/orders/{id}/cancel", order.id()).header("Authorization", ana.bearer())).andExpect(status().isOk());

            pay(ana, order.id(), "tok_ok").andExpect(status().isConflict());

            assertThat(count("SELECT count(*) FROM payments")).isZero();
        }

        @Test
        @DisplayName("if the seats somehow no longer belong to the order, nobody is charged (409)")
        void seatsNoLongerHeld() throws Exception {
            OrderResponse order = orderFixtures.place(ana, event.eventId(), seat("A1"), seat("A2"));
            // Simulate the impossible-but-defended-against: one seat now belongs to another order.
            seatFixtures.hold(floor, T0.plus(Duration.ofMinutes(5)), "A3");
            UUID otherOrder = seatFixtures.orderOf(floor, "A3");
            jdbc.update("UPDATE seats SET order_id = ? WHERE id = ?", otherOrder, seat("A2"));

            pay(ana, order.id(), "tok_ok").andExpect(status().isConflict());

            assertThat(paymentStatuses(order.id())).isEmpty();
        }

        @Test
        @DisplayName("someone else's order is 404, an unknown id is 404, a malformed id is 400")
        void ownership() throws Exception {
            OrderResponse order = orderFixtures.place(ana, event.eventId(), seat("A1"));

            pay(boris, order.id(), "tok_ok").andExpect(status().isNotFound());
            pay(ana, UUID.randomUUID(), "tok_ok").andExpect(status().isNotFound());
            mockMvc.perform(post("/orders/not-a-uuid/pay").header("Authorization", ana.bearer())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"paymentToken\":\"tok_ok\"}")).andExpect(status().isBadRequest());
            assertThat(count("SELECT count(*) FROM payments")).isZero();
        }

        @Test
        @DisplayName("organizers get 403 and anonymous callers 401")
        void whoMayPay() throws Exception {
            OrderResponse order = orderFixtures.place(ana, event.eventId(), seat("A1"));

            pay(organizer, order.id(), "tok_ok").andExpect(status().isForbidden());
            mockMvc.perform(post("/orders/{id}/pay", order.id()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"paymentToken\":\"tok_ok\"}")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("payment attempts are rate limited: the 6th in a minute is 429 (stops card-testing bots)")
        void rateLimited() throws Exception {
            OrderResponse order = orderFixtures.place(ana, event.eventId(), seat("A1"));
            for (int attempt = 1; attempt <= 5; attempt++) {
                pay(ana, order.id(), "tok_declined").andExpect(status().isPaymentRequired());
            }

            pay(ana, order.id(), "tok_ok").andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        }
    }

    // ============================================================================================
    @Nested
    @DisplayName("simultaneous requests")
    class Concurrency {

        private final ExecutorService pool = Executors.newFixedThreadPool(8);

        /** Runs the tasks at the same instant and returns their HTTP statuses. */
        private List<Integer> fireTogether(List<java.util.concurrent.Callable<Integer>> tasks) throws Exception {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (var task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
            return statuses;
        }

        private int payStatus(Account as, UUID orderId) throws Exception {
            return pay(as, orderId, "tok_ok").andReturn().getResponse().getStatus();
        }

        @Test
        @DisplayName("a customer double-clicking 'pay' (four simultaneous requests) is charged exactly ONCE")
        void doubleClick() throws Exception {
            OrderResponse order = orderFixtures.place(ana, event.eventId(), seat("A1"), seat("A2"));

            List<Integer> statuses = fireTogether(List.of(
                    () -> payStatus(ana, order.id()), () -> payStatus(ana, order.id()),
                    () -> payStatus(ana, order.id()), () -> payStatus(ana, order.id())));

            // All four get a clean answer (the later ones simply see "already paid"), and the money moved once.
            assertThat(statuses).allMatch(status -> status == 200);
            assertThat(paymentStatuses(order.id())).containsExactly("APPROVED");
            assertThat(count("SELECT count(*) FROM seats WHERE order_id = ? AND status = 'SOLD'", order.id())).isEqualTo(2);
        }

        @Test
        @DisplayName("'pay' racing with 'cancel' always ends consistently, 12 rounds: cancelled, seats free, and a refund if money was taken")
        void payVersusCancel() throws Exception {
            for (int round = 0; round < 12; round++) {
                Account customer = accounts.customer("racer" + round + "@example.com");
                String row = String.valueOf((char) ('A' + (round % 4)));
                int number = 1 + (round / 4) * 2;
                OrderResponse order = orderFixtures.place(customer, event.eventId(), seat(row + number));

                List<Integer> statuses = fireTogether(List.of(
                        () -> payStatus(customer, order.id()),
                        () -> mockMvc.perform(post("/orders/{id}/cancel", order.id()).header("Authorization", customer.bearer()))
                                .andReturn().getResponse().getStatus()));
                int payResult = statuses.get(0);
                int cancelResult = statuses.get(1);

                // The order is far from its start, so cancelling always works (even right after paying).
                assertThat(cancelResult).as("round %d cancel", round).isEqualTo(200);
                assertThat(payResult).as("round %d pay", round).isIn(200, 409);

                assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order.id()))
                        .as("round %d order", round).isEqualTo("CANCELLED");
                assertThat(seatFixtures.storedStatus(floor, row + number)).as("round %d seat", round).isEqualTo("AVAILABLE");
                // Paid-then-cancelled leaves exactly one REFUNDED payment; cancelled-first leaves none. Never an
                // APPROVED payment on a cancelled order (money taken, no ticket).
                if (payResult == 200) {
                    assertThat(paymentStatuses(order.id())).as("round %d payments", round).containsExactly("REFUNDED");
                } else {
                    assertThat(paymentStatuses(order.id())).as("round %d payments", round).isEmpty();
                }
            }
        }
    }

    // ============================================================================================
    @Nested
    @DisplayName("the database refuses a second charge whatever the application does")
    class DatabaseGuarantee {

        @Test
        @DisplayName("two APPROVED payments for one order violate the partial unique index; DECLINED ones are unlimited")
        void onlyOneSuccessfulPayment() {
            OrderResponse order = orderFixtures.place(ana, event.eventId(), seat("A1"));
            String insert = "INSERT INTO payments (id, order_id, amount_cents, status, gateway_reference, failure_reason) VALUES (gen_random_uuid(), ?, 5000, ?, ?, ?)";

            jdbc.update(insert, order.id(), "DECLINED", null, "card declined");
            jdbc.update(insert, order.id(), "DECLINED", null, "card declined again");
            jdbc.update(insert, order.id(), "APPROVED", "pay_1", null);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(insert, order.id(), "APPROVED", "pay_2", null))
                    .hasMessageContaining("payments_one_success_per_order");
            // A refunded payment is still "the" successful payment: no new APPROVED row may follow it either.
            jdbc.update("UPDATE payments SET status = 'REFUNDED', refunded_at = now() WHERE gateway_reference = 'pay_1'");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(insert, order.id(), "APPROVED", "pay_3", null))
                    .hasMessageContaining("payments_one_success_per_order");
        }

        @Test
        @DisplayName("CHECK constraints: an approved payment needs a reference; only REFUNDED has a refund date")
        void checkConstraints() {
            OrderResponse order = orderFixtures.place(ana, event.eventId(), seat("A1"));

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                            "INSERT INTO payments (id, order_id, amount_cents, status) VALUES (gen_random_uuid(), ?, 5000, 'APPROVED')", order.id()))
                    .hasMessageContaining("payments_reference_check");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                            "INSERT INTO payments (id, order_id, amount_cents, status, gateway_reference, refunded_at) VALUES (gen_random_uuid(), ?, 5000, 'APPROVED', 'pay_x', now())", order.id()))
                    .hasMessageContaining("payments_refund_consistency_check");
        }
    }
}
