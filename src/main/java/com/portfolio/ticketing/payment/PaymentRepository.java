package com.portfolio.ticketing.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findByOrderIdOrderByCreatedAt(UUID orderId);

    Optional<Payment> findByOrderIdAndStatus(UUID orderId, PaymentStatus status);
}
