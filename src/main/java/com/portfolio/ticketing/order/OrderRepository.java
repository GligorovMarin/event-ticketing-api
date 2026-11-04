package com.portfolio.ticketing.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    List<Order> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    Optional<Order> findByIdAndCustomerId(UUID id, UUID customerId);

    /**
     * The customer's own order, with its row LOCKED until the transaction ends. Pay and cancel both start with
     * this, so "pay" and "cancel" of one order can never run at the same time: the second waits, then sees the
     * state the first one left. LOCK ORDER of the whole application: the ORDER row first, then its SEATS in
     * ascending id (SeatRepository.lockAllByIdOrdered). Always the same order, so locks cannot deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id AND o.customerId = :customerId")
    Optional<Order> findOwnedForUpdate(@Param("id") UUID id, @Param("customerId") UUID customerId);

    /** Same, for the expiry sweep, which acts for the system and so has no owner to check. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Ids of PENDING orders whose hold has ended, oldest deadline first, at most one page. Served by the partial
     * index {@code orders_pending_expiry_idx} (migration V4).
     */
    @Query("""
            SELECT o.id FROM Order o
            WHERE o.status = com.portfolio.ticketing.order.OrderStatus.PENDING AND o.holdExpiresAt <= :now
            ORDER BY o.holdExpiresAt
            """)
    List<UUID> findLapsedPendingIds(@Param("now") Instant now, Pageable page);

    /**
     * Marks the customer's own PENDING orders for this event whose hold has run out as EXPIRED, so a NEW order can
     * be created (the partial unique index only allows one PENDING order per customer and event).
     *
     * <p>It deliberately touches ONLY the orders table, never the seats: the seats of a lapsed order are already
     * free in effect (lazy expiry), and not locking them here keeps the seat locks in one predictable order.
     * {@code versioned} makes the bulk UPDATE bump the optimistic-locking counter like a normal update would.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE versioned Order o SET o.status = com.portfolio.ticketing.order.OrderStatus.EXPIRED
            WHERE o.customerId = :customerId AND o.eventId = :eventId
              AND o.status = com.portfolio.ticketing.order.OrderStatus.PENDING
              AND o.holdExpiresAt <= :now
            """)
    int expireLapsedPending(@Param("customerId") UUID customerId, @Param("eventId") UUID eventId, @Param("now") Instant now);
}
