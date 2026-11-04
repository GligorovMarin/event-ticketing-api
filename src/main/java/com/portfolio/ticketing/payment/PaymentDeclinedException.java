package com.portfolio.ticketing.payment;

/**
 * The card was refused (HTTP 402 Payment Required). The order stays open so the customer can try another card
 * until the hold runs out. Unlike most exceptions this one must NOT roll back the transaction: the DECLINED payment
 * record is an audit trail we want to keep (see {@code @Transactional(noRollbackFor = ...)} in OrderLifecycleService).
 */
public class PaymentDeclinedException extends RuntimeException {

    public PaymentDeclinedException(String reason) {
        super("The payment was declined: " + reason);
    }
}
