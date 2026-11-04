package com.portfolio.ticketing.payment;

/**
 * The boundary to whatever actually moves money (Stripe, a bank ...). The rest of the application talks ONLY to
 * this interface, never to a provider directly.
 *
 * <p>This is the <b>strategy pattern</b>: one interface, interchangeable implementations. Today the only one is
 * {@link FakePaymentGateway}, which simulates a provider so the whole ordering flow can be built and tested with no
 * real money and no network. A real provider would be one new class implementing these two methods; nothing in
 * order/ would change. Tests can also substitute their own implementation to script any provider behaviour.
 *
 * <p>Both methods may throw {@link PaymentGatewayUnavailableException} when the provider cannot be reached.
 */
public interface PaymentGateway {

    /** Result of trying to take money. A declined card is a NORMAL result, not an exception. */
    record ChargeResult(boolean approved, String reference, String declineReason) {

        public static ChargeResult approved(String reference) {
            return new ChargeResult(true, reference, null);
        }

        public static ChargeResult declined(String reason) {
            return new ChargeResult(false, null, reason);
        }
    }

    /**
     * @param paymentToken what the customer's device sent instead of card details (real providers never let
     *                     card numbers touch your server)
     * @param orderReference our own order id, so the provider's records can be matched to ours
     */
    ChargeResult charge(String paymentToken, int amountCents, String orderReference);

    /** Gives money back for an earlier approved charge. */
    void refund(String reference, int amountCents);
}
