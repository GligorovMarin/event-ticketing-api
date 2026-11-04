package com.portfolio.ticketing.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** One seat inside an order, with the price it cost at that moment. A row of {@code order_items}. */
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "seat_id", nullable = false, updatable = false)
    private UUID seatId;

    @Column(name = "price_cents", nullable = false, updatable = false)
    private int priceCents;

    protected OrderItem() {
    }

    public OrderItem(UUID orderId, UUID seatId, int priceCents) {
        this.orderId = orderId;
        this.seatId = seatId;
        this.priceCents = priceCents;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getSeatId() {
        return seatId;
    }

    public int getPriceCents() {
        return priceCents;
    }
}
