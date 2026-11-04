package com.portfolio.ticketing.payment;

/** What happened to a payment. See migration V4 for the rules the database enforces. */
public enum PaymentStatus {
    APPROVED,
    DECLINED,
    REFUNDED
}
