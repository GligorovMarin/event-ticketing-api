package com.portfolio.ticketing.ratelimit;

import com.portfolio.ticketing.ratelimit.SlidingWindowRateLimiter.Decision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** UNIT test of the sliding-window algorithm with a clock the test moves by hand. No Spring, no waiting. */
class SlidingWindowRateLimiterTest {

    /** A clock that only moves when the test says so. */
    private static final class ManualClock extends Clock {
        private Instant now = Instant.parse("2026-12-01T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private static final Duration MINUTE = Duration.ofMinutes(1);

    private ManualClock clock;
    private SlidingWindowRateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new ManualClock();
        limiter = new SlidingWindowRateLimiter(clock);
    }

    @Test
    @DisplayName("allows requests up to the limit and refuses the next one")
    void limit() {
        for (int i = 1; i <= 10; i++) {
            assertThat(limiter.tryAcquire("ana", 10, MINUTE).allowed()).as("request %d", i).isTrue();
        }

        assertThat(limiter.tryAcquire("ana", 10, MINUTE).allowed()).isFalse();
    }

    @Test
    @DisplayName("reports how many requests are left")
    void remaining() {
        assertThat(limiter.tryAcquire("ana", 3, MINUTE).remaining()).isEqualTo(2);
        assertThat(limiter.tryAcquire("ana", 3, MINUTE).remaining()).isEqualTo(1);
        assertThat(limiter.tryAcquire("ana", 3, MINUTE).remaining()).isZero();
        assertThat(limiter.tryAcquire("ana", 3, MINUTE).remaining()).isZero();
    }

    @Test
    @DisplayName("tells a refused caller exactly when the oldest request leaves the window")
    void retryAfter() {
        limiter.tryAcquire("ana", 2, MINUTE);          // at 12:00:00
        clock.advance(Duration.ofSeconds(20));
        limiter.tryAcquire("ana", 2, MINUTE);          // at 12:00:20

        clock.advance(Duration.ofSeconds(10));         // now 12:00:30
        Decision refused = limiter.tryAcquire("ana", 2, MINUTE);

        // The OLDEST request (12:00:00) leaves the window at 12:01:00, which is 30 seconds from now.
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfter()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("the window SLIDES: capacity returns request by request, not all at once")
    void slides() {
        limiter.tryAcquire("ana", 2, MINUTE);          // 12:00:00
        clock.advance(Duration.ofSeconds(40));
        limiter.tryAcquire("ana", 2, MINUTE);          // 12:00:40

        clock.advance(Duration.ofSeconds(21));         // 12:01:01 -> the 12:00:00 request has left the window
        assertThat(limiter.tryAcquire("ana", 2, MINUTE).allowed()).isTrue();
        // ...but the 12:00:40 request is still inside, so only ONE slot was freed.
        assertThat(limiter.tryAcquire("ana", 2, MINUTE).allowed()).isFalse();
    }

    @Test
    @DisplayName("a request exactly one window old no longer counts")
    void exactBoundary() {
        limiter.tryAcquire("ana", 1, MINUTE);          // 12:00:00

        clock.advance(Duration.ofSeconds(59));
        assertThat(limiter.tryAcquire("ana", 1, MINUTE).allowed()).isFalse();

        clock.advance(Duration.ofSeconds(1));          // exactly 12:01:00
        assertThat(limiter.tryAcquire("ana", 1, MINUTE).allowed()).isTrue();
    }

    @Test
    @DisplayName("a burst across a minute boundary is NOT allowed (the flaw of fixed windows)")
    void noBoundaryBurst() {
        // A fixed "per calendar minute" counter would reset at 12:01:00 and allow 10 + 10 requests in two seconds.
        clock.advance(Duration.ofSeconds(59));                         // 12:00:59
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("ana", 10, MINUTE).allowed()).isTrue();
        }

        clock.advance(Duration.ofSeconds(2));                          // 12:01:01, a new calendar minute
        assertThat(limiter.tryAcquire("ana", 10, MINUTE).allowed()).isFalse();
    }

    @Test
    @DisplayName("each key has its own allowance")
    void keysAreIndependent() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("ana", 5, MINUTE);
        }

        assertThat(limiter.tryAcquire("ana", 5, MINUTE).allowed()).isFalse();
        assertThat(limiter.tryAcquire("boris", 5, MINUTE).allowed()).isTrue();
    }

    @Test
    @DisplayName("refused requests do not extend the penalty (they are not recorded)")
    void refusedRequestsAreNotCounted() {
        limiter.tryAcquire("ana", 1, MINUTE);                          // 12:00:00
        for (int i = 0; i < 50; i++) {
            limiter.tryAcquire("ana", 1, MINUTE);                      // hammering while blocked
        }

        clock.advance(MINUTE);                                         // 12:01:00

        assertThat(limiter.tryAcquire("ana", 1, MINUTE).allowed()).isTrue();
    }

    @Test
    @DisplayName("under 200 simultaneous requests for one key, exactly the limit gets through")
    void concurrentCallersNeverExceedTheLimit() throws Exception {
        int threads = 200;
        int limit = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startTogether = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startTogether.await();
                    if (limiter.tryAcquire("ana", limit, MINUTE).allowed()) {
                        allowed.incrementAndGet();
                    }
                    return null;
                }));
            }
            startTogether.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        // Not 11, not 9: the per-key lock makes check-and-record atomic.
        assertThat(allowed.get()).isEqualTo(limit);
    }

    @Test
    @DisplayName("idle keys are cleaned out once the map is large, so memory cannot grow forever")
    void idleKeysArePurged() {
        // 10,001 different users each make one request...
        for (int i = 0; i <= 10_000; i++) {
            limiter.tryAcquire("user-" + i, 5, MINUTE);
        }
        assertThat(limiter.trackedKeys()).isEqualTo(10_001);

        // ...a minute passes, and the next request triggers the housekeeping.
        clock.advance(Duration.ofMinutes(2));
        limiter.tryAcquire("someone-new", 5, MINUTE);

        assertThat(limiter.trackedKeys()).isLessThan(10);
    }
}
