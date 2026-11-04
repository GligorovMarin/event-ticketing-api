package com.portfolio.ticketing.order;

import com.portfolio.ticketing.support.DatabaseCleaner;
import com.portfolio.ticketing.support.EventFixtures;
import com.portfolio.ticketing.support.EventFixtures.BuiltEvent;
import com.portfolio.ticketing.support.EventFixtures.SectionSpec;
import com.portfolio.ticketing.support.IntegrationTest;
import com.portfolio.ticketing.support.SeatFixtures;
import com.portfolio.ticketing.support.TestAccounts;
import com.portfolio.ticketing.support.TestAccounts.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * THE concurrency tests. Many customers hit POST /orders at the same instant, against real PostgreSQL and a
 * deliberately SMALL connection pool (5, see application-test.yml), because that is when real systems break.
 *
 * <p>How the "same instant" is arranged: every thread waits on a {@link CountDownLatch}; once all are ready,
 * the latch opens and they fire together. Without it the first thread would often finish before the second starts,
 * and the test would prove nothing.
 *
 * <p>What is checked is never "it did not crash" but INVARIANTS: facts that must hold whatever the interleaving
 * (no seat held twice, every held seat belongs to a live order, only 201 and 409 are ever returned).
 */
@IntegrationTest
class OrderConcurrencyIT {

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

    private Account organizer;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        organizer = accounts.organizer("org@example.com");
        pool = Executors.newFixedThreadPool(16);
    }

    /** One attempt to order: who, which event, which seats. */
    private record Attempt(Account customer, UUID eventId, List<UUID> seatIds) {
    }

    /** What came back: the HTTP status and the body (the body says WHY a request was refused). */
    private record Result(int status, String body) {
    }

    private Result send(Attempt attempt) throws Exception {
        String ids = attempt.seatIds().stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
        var response = mockMvc.perform(post("/orders")
                        .header("Authorization", attempt.customer().bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"%s\",\"seatIds\":[%s]}".formatted(attempt.eventId(), ids)))
                .andReturn().getResponse();

        return new Result(response.getStatus(), response.getContentAsString());
    }

    /** Fires all attempts at the same moment and returns their results, in the order of the attempts. */
    private List<Result> fireTogetherDetailed(List<Attempt> attempts) throws Exception {
        CountDownLatch everyoneReady = new CountDownLatch(attempts.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Result>> futures = new ArrayList<>();

        for (Attempt attempt : attempts) {
            Callable<Result> task = () -> {
                everyoneReady.countDown();
                go.await();               // all threads park here...
                return send(attempt);
            };
            futures.add(pool.submit(task));
        }
        everyoneReady.await();
        go.countDown();                   // ...and are released at the same instant

        List<Result> results = new ArrayList<>();
        for (Future<Result> future : futures) {
            results.add(future.get());
        }
        return results;
    }

    /** Same, when only the HTTP statuses matter. */
    private List<Integer> fireTogether(List<Attempt> attempts) throws Exception {
        return fireTogetherDetailed(attempts).stream().map(Result::status).toList();
    }

    private long count(List<Integer> statuses, int status) {
        return statuses.stream().filter(s -> s == status).count();
    }

    private int countRows(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    @Test
    @DisplayName("eight customers racing for the SAME seat: exactly one wins, seven get 409, and nothing is half-created")
    void oneSeatManyCustomers() throws Exception {
        BuiltEvent event = eventFixtures.published(organizer, "Rock Night", 30, new SectionSpec("Floor", 5000, 2, 6));
        UUID seat = seatFixtures.seatId(event.section("Floor"), "A1");
        List<Attempt> attempts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            attempts.add(new Attempt(accounts.customer("racer" + i + "@example.com"), event.eventId(), List.of(seat)));
        }

        List<Integer> statuses = fireTogether(attempts);

        assertThat(count(statuses, 201)).as("winners").isEqualTo(1);
        assertThat(count(statuses, 409)).as("clean refusals").isEqualTo(7);
        // The losers' transactions rolled back COMPLETELY: only the winner's order and item exist.
        assertThat(countRows("SELECT count(*) FROM orders")).isEqualTo(1);
        assertThat(countRows("SELECT count(*) FROM order_items")).isEqualTo(1);
        assertThat(seatFixtures.storedStatus(event.section("Floor"), "A1")).isEqualTo("HELD");
    }

    @Test
    @DisplayName("opposite seat orders [A1,A2] vs [A2,A1], 20 rounds: never a deadlock, one winner per round")
    void oppositeLockOrderDoesNotDeadlock() throws Exception {
        BuiltEvent event = eventFixtures.published(organizer, "Rock Night", 30, new SectionSpec("Floor", 5000, 20, 2));
        UUID floor = event.section("Floor");

        for (int round = 0; round < 20; round++) {
            String row = String.valueOf((char) ('A' + round));
            UUID first = seatFixtures.seatId(floor, row + "1");
            UUID second = seatFixtures.seatId(floor, row + "2");
            Account one = accounts.customer("one-" + round + "@example.com");
            Account two = accounts.customer("two-" + round + "@example.com");

            // Customer one asks for (first, second); customer two asks for (second, first). If each LOCKED in the
            // order it was asked, they would each hold one seat and wait for the other: a deadlock.
            List<Result> results = fireTogetherDetailed(List.of(
                    new Attempt(one, event.eventId(), List.of(first, second)),
                    new Attempt(two, event.eventId(), List.of(second, first))));

            assertThat(results.stream().map(Result::status).toList()).as("round %d", round).containsExactlyInAnyOrder(201, 409);

            // WHY the loser lost matters. With ordered locking it waits for the winner and then finds the seats
            // TAKEN. If it had been a deadlock victim (or timed out), the message would be "busy right now"
            // instead: PostgreSQL would also answer 409 there, so the status code alone cannot tell them apart.
            String loserBody = results.stream().filter(r -> r.status() == 409).findFirst().orElseThrow().body();
            assertThat(loserBody).as("round %d", round).contains("not available").doesNotContain("busy right now");
        }
        assertThat(countRows("SELECT count(*) FROM orders")).isEqualTo(20);
    }

    @Test
    @DisplayName("one customer sending five orders at once: exactly one order exists afterwards (partial unique index)")
    void sameCustomerManyOrders() throws Exception {
        BuiltEvent event = eventFixtures.published(organizer, "Rock Night", 30, new SectionSpec("Floor", 5000, 5, 5));
        UUID floor = event.section("Floor");
        Account ana = accounts.customer("ana@example.com");
        List<Attempt> attempts = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            attempts.add(new Attempt(ana, event.eventId(), List.of(seatFixtures.seatId(floor, "A" + i))));
        }

        List<Integer> statuses = fireTogether(attempts);

        assertThat(count(statuses, 201)).isEqualTo(1);
        assertThat(count(statuses, 409)).isEqualTo(4);
        assertThat(countRows("SELECT count(*) FROM orders WHERE customer_id = ?", ana.id())).isEqualTo(1);
        // Only the winner's single seat is held; the four losers left no trace.
        assertThat(countRows("SELECT count(*) FROM seats WHERE status = 'HELD'")).isEqualTo(1);
    }

    @Test
    @DisplayName("random contention: 15 customers each want 3 of only 12 seats; afterwards no seat is double-held and nothing is orphaned")
    void randomContentionKeepsEveryInvariant() throws Exception {
        BuiltEvent event = eventFixtures.published(organizer, "Rock Night", 30, new SectionSpec("Floor", 5000, 2, 6));
        UUID floor = event.section("Floor");
        List<UUID> allSeats = new ArrayList<>();
        for (String row : List.of("A", "B")) {
            for (int number = 1; number <= 6; number++) {
                allSeats.add(seatFixtures.seatId(floor, row + number));
            }
        }

        Random random = new Random(42); // a fixed seed: the same contention pattern on every run
        List<Attempt> attempts = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            List<UUID> wanted = new ArrayList<>(allSeats);
            Collections.shuffle(wanted, random);
            attempts.add(new Attempt(accounts.customer("c" + i + "@example.com"), event.eventId(), wanted.subList(0, 3)));
        }

        List<Integer> statuses = fireTogether(attempts);

        // 1. Only two outcomes exist: created, or a clean conflict. Never a 500, never a deadlock error.
        assertThat(statuses).allMatch(status -> status == 201 || status == 409);
        long winners = count(statuses, 201);
        assertThat(winners).as("12 seats / 3 per order = at most 4 winners").isBetween(1L, 4L);

        // 2. No seat belongs to two live orders.
        assertThat(countRows("""
                SELECT count(*) FROM (
                    SELECT oi.seat_id FROM order_items oi JOIN orders o ON o.id = oi.order_id
                    WHERE o.status = 'PENDING' GROUP BY oi.seat_id HAVING count(*) > 1) doubled
                """)).as("seats in more than one pending order").isZero();

        // 3. The number of held seats equals the number of items in pending orders (no orphans either way).
        assertThat(countRows("SELECT count(*) FROM seats WHERE status = 'HELD'")).isEqualTo((int) winners * 3);
        assertThat(countRows("""
                SELECT count(*) FROM order_items oi JOIN orders o ON o.id = oi.order_id WHERE o.status = 'PENDING'
                """)).isEqualTo((int) winners * 3);

        // 4. Every held seat points at a pending order that really contains it.
        assertThat(countRows("""
                SELECT count(*) FROM seats s
                WHERE s.status = 'HELD' AND NOT EXISTS (
                    SELECT 1 FROM order_items oi JOIN orders o ON o.id = oi.order_id
                    WHERE oi.seat_id = s.id AND oi.order_id = s.order_id AND o.status = 'PENDING')
                """)).as("held seats without a matching pending order").isZero();

        // 5. One order per winner, and every order total is 3 x 5000.
        assertThat(countRows("SELECT count(*) FROM orders")).isEqualTo((int) winners);
        assertThat(countRows("SELECT count(*) FROM orders WHERE total_cents <> 15000")).isZero();
    }
}
