package com.portfolio.ticketing.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Housekeeping: every minute, frees the seats of orders whose 10-minute hold ended and marks those orders EXPIRED.
 *
 * <p><b>Correctness never depends on this job.</b> A lapsed hold already counts as free the moment it ends (lazy
 * expiry in SeatView and Order.effectiveStatus), so nobody is ever blocked by a seat the sweep has not got to yet.
 * The sweep only tidies the stored data, keeps reports tidy, and releases seats nobody asks about.
 *
 * <p>Each order is expired in its own short transaction, and a failure on one order is logged and skipped so a single
 * problem row cannot stop the rest. It is safe to run on several servers at once, or to overlap with payment and
 * cancellation: {@link OrderLifecycleService#expire} locks the order row and re-checks first.
 *
 * <p>Switched on by config/SchedulingConfig (off in tests, which call {@link #sweepOnce()} themselves).
 */
@Component
public class OrderExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(OrderExpirySweeper.class);

    /** At most this many orders per run, so one run stays short; the next run continues where this one stopped. */
    private static final int BATCH_SIZE = 200;

    private final OrderRepository orders;
    private final OrderLifecycleService lifecycle;
    private final Clock clock;

    public OrderExpirySweeper(OrderRepository orders, OrderLifecycleService lifecycle, Clock clock) {
        this.orders = orders;
        this.lifecycle = lifecycle;
        this.clock = clock;
    }

    /** fixedDelay: wait one interval AFTER the previous run finished, so runs never overlap on this server. */
    @Scheduled(fixedDelayString = "${app.orders.sweep-interval:PT1M}")
    public void scheduledSweep() {
        try {
            int expired = sweepOnce();
            if (expired > 0) {
                log.info("Expired {} order(s) whose seat hold ended", expired);
            }
        } catch (RuntimeException e) {
            // An exception escaping a scheduled method is logged by Spring and the schedule continues; we log
            // it ourselves with context.
            log.error("Sweeping expired orders failed", e);
        }
    }

    /** One pass. Returns how many orders were expired. Public so tests (and operators) can run it on demand. */
    public int sweepOnce() {
        List<UUID> lapsed = orders.findLapsedPendingIds(clock.instant(), PageRequest.of(0, BATCH_SIZE));

        int expired = 0;
        for (UUID orderId : lapsed) {
            try {
                if (lifecycle.expire(orderId)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not expire order {}; will retry on the next run", orderId, e);
            }
        }
        return expired;
    }
}
