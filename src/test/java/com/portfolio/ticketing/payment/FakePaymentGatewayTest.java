package com.portfolio.ticketing.payment;

import com.portfolio.ticketing.common.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** UNIT test of the pretend payment provider: each token must reliably produce its documented outcome. */
class FakePaymentGatewayTest {

    private final FakePaymentGateway gateway = new FakePaymentGateway();

    @Test
    @DisplayName("tok_ok is approved and gets a provider reference")
    void approved() {
        PaymentGateway.ChargeResult result = gateway.charge("tok_ok", 5000, "order-1");

        assertThat(result.approved()).isTrue();
        assertThat(result.reference()).startsWith("pay_");
        assertThat(result.declineReason()).isNull();
    }

    @Test
    @DisplayName("every approved charge gets a DIFFERENT reference")
    void referencesAreUnique() {
        assertThat(gateway.charge("tok_ok", 100, "a").reference()).isNotEqualTo(gateway.charge("tok_ok", 100, "a").reference());
    }

    @Test
    @DisplayName("tok_declined is a declined result with a reason: a normal outcome, not an exception")
    void declined() {
        PaymentGateway.ChargeResult result = gateway.charge("tok_declined", 5000, "order-1");

        assertThat(result.approved()).isFalse();
        assertThat(result.reference()).isNull();
        assertThat(result.declineReason()).isNotBlank();
    }

    @Test
    @DisplayName("tok_timeout means the provider is down")
    void providerDown() {
        assertThatThrownBy(() -> gateway.charge("tok_timeout", 5000, "order-1"))
                .isInstanceOf(PaymentGatewayUnavailableException.class);
    }

    @Test
    @DisplayName("an unknown token is a caller error")
    void unknownToken() {
        assertThatThrownBy(() -> gateway.charge("tok_whatever", 5000, "order-1")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> gateway.charge("", 5000, "order-1")).isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("a normal payment can be refunded")
    void refundSucceeds() {
        String reference = gateway.charge("tok_ok", 5000, "order-1").reference();

        assertThatCode(() -> gateway.refund(reference, 5000)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("tok_refund_fails is approved, but refunding that payment fails")
    void refundFails() {
        PaymentGateway.ChargeResult result = gateway.charge("tok_refund_fails", 5000, "order-1");

        assertThat(result.approved()).isTrue();
        assertThatThrownBy(() -> gateway.refund(result.reference(), 5000)).isInstanceOf(PaymentGatewayUnavailableException.class);
    }
}
