package com.portfolio.ticketing.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** UNIT test of an order's "effective" status (lazy expiry): what the order IS now, not what the row says. */
class OrderTest {

    private static final Instant NOW = Instant.parse("2026-12-01T12:00:00Z");

    private static Order pendingOrder(Instant holdExpiresAt) {
        return new Order(UUID.randomUUID(), UUID.randomUUID(), 5000, holdExpiresAt);
    }

    @Test
    @DisplayName("a pending order whose hold is still running is PENDING")
    void stillPending() {
        assertThat(pendingOrder(NOW.plus(Duration.ofSeconds(1))).effectiveStatus(NOW)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("a pending order whose hold ran out is EXPIRED, although the stored status still says PENDING")
    void lapsedIsExpired() {
        Order order = pendingOrder(NOW.minus(Duration.ofSeconds(1)));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.effectiveStatus(NOW)).isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    @DisplayName("a hold ending exactly now is over")
    void endingNowIsOver() {
        assertThat(pendingOrder(NOW).effectiveStatus(NOW)).isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    @DisplayName("a new order has the PENDING status and a first version of zero")
    void newOrderDefaults() {
        Order order = pendingOrder(NOW);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getVersion()).isZero();
        assertThat(order.getTotalCents()).isEqualTo(5000);
    }
}
