package com.portfolio.ticketing.order;

import org.springframework.data.jpa.repository.JpaRepository;
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
