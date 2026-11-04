package com.portfolio.ticketing.support;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock tests can move. By default it follows the real time, so tests that do not care are unaffected.
 *
 * <p>{@code @Primary} makes Spring inject THIS clock instead of the real one (config/ClockConfig) everywhere the
 * application asks for a {@link Clock}: the order service, the rate limiter, the availability view. A test can then
 * say "ten minutes later" and watch holds expire without waiting.
 *
 * <p>{@link DatabaseCleaner#clean()} calls {@link #reset()}, so one test's time travel never leaks into the next.
 */
@Component
@Primary
@Profile("test")
public class TestClock extends Clock {

    /** null = follow the real time; otherwise time is frozen at this instant. */
    private volatile Instant frozenAt;

    public void freezeAt(Instant instant) {
        this.frozenAt = instant;
    }

    /** Moves time forward from the current reading (frozen or real) and freezes it there. */
    public void advance(Duration duration) {
        this.frozenAt = instant().plus(duration);
    }

    public void reset() {
        this.frozenAt = null;
    }

    @Override
    public Instant instant() {
        Instant frozen = frozenAt;
        return frozen != null ? frozen : Instant.now();
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
