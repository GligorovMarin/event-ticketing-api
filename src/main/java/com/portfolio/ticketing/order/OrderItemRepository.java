package com.portfolio.ticketing.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    List<OrderItem> findByOrderId(UUID orderId);

    /** The items of MANY orders in one query (used to show a list of orders without N+1 queries). */
    List<OrderItem> findByOrderIdIn(Collection<UUID> orderIds);
}
