package com.portfolio.ticketing.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** UNIT test of the cancellation window for PAID orders: exactly 48 hours before the event, inclusive. */
class OrderRulesTest {

    private static final Instant EVENT_START = Instant.parse("2026-12-20T19:00:00Z");

    @Test
    @DisplayName("well before the window closes, cancelling is allowed")
    void farAhead() {
        assertThat(OrderRules.canCancelPaidOrder(EVENT_START, EVENT_START.minus(Duration.ofDays(10)))).isTrue();
    }

    @Test
    @DisplayName("exactly 48 hours before the start is still allowed")
    void exactlyOnTheLimit() {
        assertThat(OrderRules.canCancelPaidOrder(EVENT_START, EVENT_START.minus(Duration.ofHours(48)))).isTrue();
    }

    @Test
    @DisplayName("one second inside the 48 hours is too late")
    void oneSecondTooLate() {
        assertThat(OrderRules.canCancelPaidOrder(EVENT_START, EVENT_START.minus(Duration.ofHours(48)).plusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("on the day of the event, and after it started, cancelling is not allowed")
    void closeToOrAfterTheStart() {
        assertThat(OrderRules.canCancelPaidOrder(EVENT_START, EVENT_START.minus(Duration.ofHours(3)))).isFalse();
        assertThat(OrderRules.canCancelPaidOrder(EVENT_START, EVENT_START)).isFalse();
        assertThat(OrderRules.canCancelPaidOrder(EVENT_START, EVENT_START.plusSeconds(60))).isFalse();
    }
}
