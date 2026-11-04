package com.portfolio.ticketing.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code orders} (migration V3). The ordering rules live in {@link OrderService}.
 *
 * <p>Two kinds of locking protect an order (a good interview topic):
 * <ul>
 *   <li><b>pessimistic</b> (seats): "lock the rows first, then decide". Used while taking seats, because
 *       conflicts there are likely (many people want the same seat) and the cost of a conflict is high;</li>
 *   <li><b>optimistic</b> ({@link #version}): "just do it, and fail if someone else changed the row meanwhile".
 *       Used on the order itself, where conflicts (pay vs. cancel at the same instant) are rare.</li>
 * </ul>
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "total_cents", nullable = false, updatable = false)
    private int totalCents;

    @Column(name = "hold_expires_at", nullable = false, updatable = false)
    private Instant holdExpiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /** Optimistic-locking counter, managed entirely by Hibernate. */
    @Version
    private long version;

    protected Order() {
    }

    public Order(UUID customerId, UUID eventId, int totalCents, Instant holdExpiresAt) {
        this.customerId = customerId;
        this.eventId = eventId;
        this.totalCents = totalCents;
        this.holdExpiresAt = holdExpiresAt;
    }

    /**
     * The status as of {@code now}. The stored status can lag behind reality: a PENDING order whose hold has
     * run out is really EXPIRED, even before any cleanup job has written that down ("lazy expiry"). Everything
     * that SHOWS or DECIDES on an order uses this, never the raw status.
     */
    public OrderStatus effectiveStatus(Instant now) {
        boolean holdHasLapsed = status == OrderStatus.PENDING && !holdExpiresAt.isAfter(now);

        return holdHasLapsed ? OrderStatus.EXPIRED : status;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public int getTotalCents() {
        return totalCents;
    }

    public Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public long getVersion() {
        return version;
    }
}
