package com.portfolio.ticketing.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** One attempt to pay for an order. A row of {@code payments} (migration V4). */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "amount_cents", nullable = false, updatable = false)
    private int amountCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "gateway_reference", length = 100)
    private String gatewayReference;

    @Column(name = "failure_reason", length = 200)
    private String failureReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    protected Payment() {
    }

    private Payment(UUID orderId, int amountCents, PaymentStatus status, String gatewayReference, String failureReason) {
        this.orderId = orderId;
        this.amountCents = amountCents;
        this.status = status;
        this.gatewayReference = gatewayReference;
        this.failureReason = failureReason;
    }

    /** Named factory methods say what is being created, which a bare constructor with five arguments does not. */
    public static Payment approved(UUID orderId, int amountCents, String gatewayReference) {
        return new Payment(orderId, amountCents, PaymentStatus.APPROVED, gatewayReference, null);
    }

    public static Payment declined(UUID orderId, int amountCents, String reason) {
        return new Payment(orderId, amountCents, PaymentStatus.DECLINED, null, reason);
    }

    /** APPROVED -> REFUNDED, after the gateway has given the money back. */
    public void markRefunded(Instant now) {
        this.status = PaymentStatus.REFUNDED;
        this.refundedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public int getAmountCents() {
        return amountCents;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getGatewayReference() {
        return gatewayReference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRefundedAt() {
        return refundedAt;
    }
}
