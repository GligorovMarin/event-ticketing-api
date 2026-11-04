package com.portfolio.ticketing.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A <b>sliding-window-log</b> rate limiter: "at most {@code limit} requests per {@code window}, for each key".
 *
 * <h3>How it works</h3>
 * For every key (here: one user + one endpoint) it keeps a queue of the TIMES of that key's recent requests.
 * On a new request it first throws away times older than the window, then:
 * <ul>
 *   <li>fewer than {@code limit} left in the queue -> allow it, and add the current time;</li>
 *   <li>otherwise -> refuse, and tell the caller when the OLDEST request in the window will drop out.</li>
 * </ul>
 * "Sliding" means the window always ends at THIS moment, so there is no instant at which a burst can sneak
 * through. A <i>fixed</i> window (counter that resets every minute) would let a user make 10 requests at 12:00:59
 * and 10 more at 12:01:00: 20 requests in two seconds while never exceeding "10 per minute".
 *
 * <h3>Data structure and complexity</h3>
 * A hash map from key to a double-ended queue ({@link ArrayDeque}). Requests arrive in time order, so the oldest
 * time is always at the FRONT and a new one is added at the BACK: both are O(1), and expired times are removed
 * from the front. Each time is added once and removed at most once, so the cost per request is <b>amortised O(1)</b>.
 * Memory is O(limit) per active key, because the queue never holds more than {@code limit} times.
 *
 * <h3>Limits of this design (worth saying out loud in an interview)</h3>
 * The counters live in THIS server's memory. With several servers behind a load balancer each would count
 * separately, so the real limit would be limit x servers; a shared store such as Redis fixes that. This class is
 * the algorithm; where the counters live is a separate decision.
 *
 * <p>Used by {@link RateLimitInterceptor}. The {@link Clock} is injected so tests can move time.
 */
public class SlidingWindowRateLimiter {

    /** Above this many keys, idle ones are cleaned out so the map cannot grow forever. */
    private static final int PURGE_THRESHOLD = 10_000;

    /** The verdict for one request. */
    public record Decision(boolean allowed, int remaining, Duration retryAfter) {
    }

    /** One key's recent request times plus the window they belong to. All access is synchronised on the bucket. */
    private static final class Bucket {
        private final Deque<Long> hitTimesMillis = new ArrayDeque<>();
        private final long windowMillis;
        /** Set when the purge removed this bucket from the map; a thread that still holds it must fetch a fresh one. */
        private boolean retired;

        private Bucket(long windowMillis) {
            this.windowMillis = windowMillis;
        }

        /** Drops the times that fell out of the window. Must be called while holding the lock. */
        private void dropExpired(long nowMillis) {
            long windowStart = nowMillis - windowMillis;
            // "<=": a request made exactly one window ago no longer counts, so the limit frees up at that very instant.
            while (!hitTimesMillis.isEmpty() && hitTimesMillis.peekFirst() <= windowStart) {
                hitTimesMillis.pollFirst();
            }
        }
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock;

    public SlidingWindowRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Records a request for {@code key} if allowed. Thread-safe: concurrent callers never exceed the limit. */
    public Decision tryAcquire(String key, int limit, Duration window) {
        long nowMillis = clock.millis();

        while (true) {
            Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(window.toMillis()));

            // One lock per key: requests for different keys never wait for each other.
            synchronized (bucket) {
                if (bucket.retired) {
                    continue; // the purge removed this bucket a moment ago; loop to get a fresh one
                }

                bucket.dropExpired(nowMillis);

                if (bucket.hitTimesMillis.size() < limit) {
                    bucket.hitTimesMillis.addLast(nowMillis);
                    maybePurge(nowMillis);
                    return new Decision(true, limit - bucket.hitTimesMillis.size(), Duration.ZERO);
                }

                // Full: the oldest request leaves the window at (its time + window).
                long retryAfterMillis = bucket.hitTimesMillis.peekFirst() + bucket.windowMillis - nowMillis;
                return new Decision(false, 0, Duration.ofMillis(Math.max(1, retryAfterMillis)));
            }
        }
    }

    /** Number of keys currently tracked (for tests and monitoring). */
    public int trackedKeys() {
        return buckets.size();
    }

    /** Housekeeping: only runs when the map is large, and removes keys that have no request inside their window. */
    private void maybePurge(long nowMillis) {
        if (buckets.size() <= PURGE_THRESHOLD) {
            return;
        }

        buckets.forEach((key, bucket) -> {
            // tryLock-style courtesy: skip a bucket that is busy right now instead of waiting for it.
            if (!Thread.holdsLock(bucket)) {
                synchronized (bucket) {
                    bucket.dropExpired(nowMillis);
                    if (bucket.hitTimesMillis.isEmpty()) {
                        bucket.retired = true;
                        buckets.remove(key, bucket);
                    }
                }
            }
        });
    }
}
