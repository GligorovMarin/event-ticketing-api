package com.portfolio.ticketing.payment;

import com.portfolio.ticketing.common.BadRequestException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * A pretend payment provider, so the whole flow works without real money.
 *
 * <p>The "payment token" decides what happens, which makes every outcome easy to reproduce from Swagger or a test:
 * <pre>
 *   tok_ok              the charge is approved
 *   tok_declined        the card is declined (HTTP 402)
 *   tok_timeout         the provider is down (HTTP 503), nothing is charged
 *   tok_refund_fails    approved, but REFUNDING that payment later fails (to test a failed refund)
 *   anything else       400, unknown token
 * </pre>
 * Real providers behave the same way in test mode (Stripe's "tok_visa", "tok_chargeDeclined" ...).
 *
 * <p>Selected by Spring because it is the only {@link PaymentGateway} bean. To use a real provider, add its class
 * and mark it {@code @Primary} (or remove this one).
 */
@Component
public class FakePaymentGateway implements PaymentGateway {

    /** References of charges made with tok_refund_fails contain this marker; refunding them then fails. */
    static final String UNREFUNDABLE_MARKER = "unrefundable";

    @Override
    public ChargeResult charge(String paymentToken, int amountCents, String orderReference) {
        return switch (paymentToken) {
            case "tok_ok" -> ChargeResult.approved("pay_" + UUID.randomUUID());
            case "tok_refund_fails" -> ChargeResult.approved("pay_" + UNREFUNDABLE_MARKER + "_" + UUID.randomUUID());
            case "tok_declined" -> ChargeResult.declined("the card was declined");
            case "tok_timeout" -> throw new PaymentGatewayUnavailableException(
                    "The payment provider is not responding. You have not been charged, please try again");
            default -> throw new BadRequestException("Unknown payment token. In this demo use tok_ok, tok_declined or tok_timeout");
        };
    }

    @Override
    public void refund(String reference, int amountCents) {
        if (reference.contains(UNREFUNDABLE_MARKER)) {
            throw new PaymentGatewayUnavailableException(
                    "The payment provider could not process the refund. Nothing was changed, please try again");
        }
        // Otherwise the refund simply succeeds.
    }
}
