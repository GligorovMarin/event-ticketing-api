package com.portfolio.ticketing.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /orders/{id}/pay}.
 *
 * <p>The client sends a payment TOKEN, never card numbers: a real provider's browser/mobile library turns card
 * details into a token that only the provider can use, so card data never touches this server (which keeps us out
 * of the heaviest PCI compliance obligations). In this demo the token picks the outcome of the fake provider:
 * {@code tok_ok}, {@code tok_declined}, {@code tok_timeout} (see payment/FakePaymentGateway).
 */
public record PayOrderRequest(@NotBlank @Size(max = 100) String paymentToken) {

    public PayOrderRequest {
        paymentToken = paymentToken == null ? null : paymentToken.trim();
    }
}
