package com.portfolio.ticketing.payment;

/** The payment provider could not be reached or failed (HTTP 503). Nothing was charged or refunded. */
public class PaymentGatewayUnavailableException extends RuntimeException {

    public PaymentGatewayUnavailableException(String message) {
        super(message);
    }
}
