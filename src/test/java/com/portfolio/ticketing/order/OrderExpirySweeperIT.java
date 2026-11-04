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
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
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

/**
 * INTEGRATION test of the housekeeping job that frees seats of orders whose hold ended. The job's timer is switched
 * off in tests (application-test.yml); the tests call {@code sweepOnce()} themselves, so every run is deterministic.
 */
@IntegrationTest
class OrderExpirySweeperIT {

    private static final Instant T0 = Instant.parse("2026-12-01T12:00:00Z");

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
    private OrderExpirySweeper sweeper;
    @Autowired
    private TestClock clock;
    @Autowired
    private ApplicationContext context;

    private Account organizer;
    private BuiltEvent event;
    private UUID floor;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        clock.freezeAt(T0);
        organizer = accounts.organizer("org@example.com");
        event = eventFixtures.published(organizer, "Rock Night", 30, new SectionSpec("Floor", 5000, 4, 6));
        floor = event.section("Floor");
    }

    private UUID seat(String label) {
        return seatFixtures.seatId(floor, label);
    }

    private String orderStatus(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    @Test
    @DisplayName("an order whose hold ended becomes EXPIRED and its seats go back on sale")
    void expiresLapsedOrders() {
        OrderResponse order = orderFixtures.place(accounts.customer("ana@example.com"), event.eventId(), seat("A1"), seat("A2"));
        clock.advance(Duration.ofMinutes(10));

        int expired = sweeper.sweepOnce();

        assertThat(expired).isEqualTo(1);
        assertThat(orderStatus(order.id())).isEqualTo("EXPIRED");
        assertThat(seatFixtures.storedStatus(floor, "A1")).isEqualTo("AVAILABLE");
        assertThat(seatFixtures.orderOf(floor, "A1")).isNull();
        assertThat(seatFixtures.storedStatus(floor, "A2")).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("an order whose hold is still running is left alone (one second before the end)")
    void leavesRunningHolds() {
        OrderResponse order = orderFixtures.place(accounts.customer("ana@example.com"), event.eventId(), seat("A1"));
        clock.advance(Duration.ofMinutes(10).minusSeconds(1));

        assertThat(sweeper.sweepOnce()).isZero();

        assertThat(orderStatus(order.id())).isEqualTo("PENDING");
        assertThat(seatFixtures.storedStatus(floor, "A1")).isEqualTo("HELD");
    }

    @Test
    @DisplayName("PAID and CANCELLED orders are never touched, however old")
    void leavesFinishedOrders() {
        Account ana = accounts.customer("ana@example.com");
        Account boris = accounts.customer("boris@example.com");
        OrderResponse paid = orderFixtures.placeAndPay(ana, event.eventId(), seat("A1"));
        OrderResponse pending = orderFixtures.place(boris, event.eventId(), seat("B1"));
        jdbc.update("UPDATE orders SET status = 'CANCELLED', cancelled_at = now() WHERE id = ?", pending.id());
        jdbc.update("UPDATE seats SET status = 'AVAILABLE', order_id = NULL, hold_expires_at = NULL WHERE order_id = ?", pending.id());
        clock.advance(Duration.ofDays(5));

        assertThat(sweeper.sweepOnce()).isZero();

        assertThat(orderStatus(paid.id())).isEqualTo("PAID");
        assertThat(seatFixtures.storedStatus(floor, "A1")).isEqualTo("SOLD");
        assertThat(orderStatus(pending.id())).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("it is idempotent: a second run finds nothing left to do")
    void secondRunDoesNothing() {
        orderFixtures.place(accounts.customer("ana@example.com"), event.eventId(), seat("A1"));
        clock.advance(Duration.ofMinutes(11));

        assertThat(sweeper.sweepOnce()).isEqualTo(1);
        assertThat(sweeper.sweepOnce()).isZero();
    }

    @Test
    @DisplayName("it handles many orders in one run")
    void manyOrders() {
        for (int i = 0; i < 6; i++) {
            orderFixtures.place(accounts.customer("c" + i + "@example.com"), event.eventId(), seat("A" + (i + 1)));
        }
        clock.advance(Duration.ofMinutes(11));

        assertThat(sweeper.sweepOnce()).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seats WHERE status <> 'AVAILABLE'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("a seat already taken over by another customer is NOT freed when the old order expires")
    void doesNotStealTheNewOwnersSeat() {
        Account ana = accounts.customer("ana@example.com");
        Account boris = accounts.customer("boris@example.com");
        OrderResponse anasOrder = orderFixtures.place(ana, event.eventId(), seat("A1"), seat("A2"));
        clock.advance(Duration.ofMinutes(11));
        // Boris takes over A1 (lazy expiry); A2 stays with Ana's lapsed order.
        OrderResponse borisOrder = orderFixtures.place(boris, event.eventId(), seat("A1"));

        sweeper.sweepOnce();

        assertThat(orderStatus(anasOrder.id())).isEqualTo("EXPIRED");
        assertThat(seatFixtures.orderOf(floor, "A1")).isEqualTo(borisOrder.id()); // Boris keeps A1
        assertThat(seatFixtures.storedStatus(floor, "A1")).isEqualTo("HELD");
        assertThat(seatFixtures.storedStatus(floor, "A2")).isEqualTo("AVAILABLE"); // only Ana's own leftover was freed
        assertThat(orderStatus(borisOrder.id())).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("sweeping WHILE customers grab the same lapsed seats ends consistent: no seat belongs to an expired order")
    void sweepRacingWithNewOrders() throws Exception {
        List<Account> oldCustomers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Account customer = accounts.customer("old" + i + "@example.com");
            oldCustomers.add(customer);
            orderFixtures.place(customer, event.eventId(), seat("A" + (i + 1)), seat("B" + (i + 1)));
        }
        clock.advance(Duration.ofMinutes(11)); // every one of those holds has lapsed

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(7);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return sweeper.sweepOnce();
            }));
            // Six new customers, each wanting seats that old (lapsed) orders hold.
            for (int i = 0; i < 6; i++) {
                Account fresh = accounts.customer("fresh" + i + "@example.com");
                int column = i + 1;
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        orderFixtures.place(fresh, event.eventId(), seat("A" + column), seat("B" + column));
                        return 1;
                    } catch (RuntimeException conflictOrBusy) {
                        return 0;
                    }
                }));
            }
            ready.await();
            go.countDown();
            for (Future<Integer> result : results) {
                result.get();
            }
        } finally {
            pool.shutdownNow();
        }
        sweeper.sweepOnce(); // tidy whatever the race left

        // 1. No seat points at a finished-in-effect order.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM seats s JOIN orders o ON o.id = s.order_id
                WHERE s.status = 'HELD' AND o.status <> 'PENDING'
                """, Integer.class)).as("held seats of non-pending orders").isZero();
        // 2. Every HELD seat belongs to an order that really contains it.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM seats s WHERE s.status = 'HELD' AND NOT EXISTS (
                    SELECT 1 FROM order_items oi WHERE oi.seat_id = s.id AND oi.order_id = s.order_id)
                """, Integer.class)).as("held seats without a matching item").isZero();
        // 3. All six old orders are expired, and no seat is in two live orders.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE customer_id IN (SELECT id FROM users WHERE email LIKE 'old%')"
                + " AND status = 'EXPIRED'", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT oi.seat_id FROM order_items oi JOIN orders o ON o.id = oi.order_id
                WHERE o.status = 'PENDING' GROUP BY oi.seat_id HAVING count(*) > 1) d
                """, Integer.class)).isZero();
    }

    @Test
    @DisplayName("the sweep is a real @Scheduled job, set to run every minute by default")
    void isScheduled() throws Exception {
        Method method = OrderExpirySweeper.class.getMethod("scheduledSweep");
        Scheduled schedule = method.getAnnotation(Scheduled.class);

        assertThat(schedule).isNotNull();
        assertThat(schedule.fixedDelayString()).contains("app.orders.sweep-interval").contains("PT1M");
        // ...but the timer itself is off while tests run (app.scheduling.enabled=false).
        assertThat(context.getBeanNamesForType(org.springframework.scheduling.config.ScheduledTaskHolder.class)).isEmpty();
    }
}
