package com.portfolio.ticketing.availability;

import com.portfolio.ticketing.event.SeatStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** UNIT test of "lazy expiry": what a seat's status IS right now, given the stored status and the clock. */
class SeatViewTest {

    private static final Instant NOW = Instant.parse("2026-12-01T12:00:00Z");

    @Test
    @DisplayName("a hold that is still running stays HELD")
    void activeHoldIsHeld() {
        assertThat(SeatView.effectiveStatus(SeatStatus.HELD, NOW.plusSeconds(1), NOW)).isEqualTo(SeatStatus.HELD);
    }

    @Test
    @DisplayName("a hold that ended in the past counts as AVAILABLE, even though the row still says HELD")
    void lapsedHoldIsAvailable() {
        assertThat(SeatView.effectiveStatus(SeatStatus.HELD, NOW.minusSeconds(1), NOW)).isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    @DisplayName("a hold that ends exactly NOW is over: the seat is available from that very instant")
    void holdEndingNowIsOver() {
        assertThat(SeatView.effectiveStatus(SeatStatus.HELD, NOW, NOW)).isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    @DisplayName("SOLD stays SOLD whatever the date, and AVAILABLE stays AVAILABLE")
    void otherStatusesAreUnchanged() {
        assertThat(SeatView.effectiveStatus(SeatStatus.SOLD, null, NOW)).isEqualTo(SeatStatus.SOLD);
        assertThat(SeatView.effectiveStatus(SeatStatus.AVAILABLE, null, NOW)).isEqualTo(SeatStatus.AVAILABLE);
    }
}
