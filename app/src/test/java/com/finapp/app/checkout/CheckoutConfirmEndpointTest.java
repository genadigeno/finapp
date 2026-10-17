package com.finapp.app.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.checkout.CheckoutSession;
import com.finapp.checkout.CheckoutSessionStatus;
import com.finapp.checkout.CheckoutSessionToken;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The customer's confirmation door and BNPL, hermetic (`P12-TSK-007`; PHASE_12_PLAN.md sections 2.1, 3.3 and 5.2a):
 * the door's two decisions BNPL changes, as {@link CheckoutService#confirm} takes them before anything is read or
 * written - the instrument choice, and the refusal of a session a BNPL purchase financed.
 *
 * <p>"BNPL" is <strong>not a payment confirmation</strong>: the plan's "routed to the BNPL application" is read as "the
 * application is its own door" (`P12-TSK-010`), never a payment intent or a "BNPL rail" - so the confirmation answers
 * {@code "BNPL"} the {@code 422 platform.ValidationFailed} any unknown instrument gets (the backlog's "400" read as the
 * door's existing refusal), before anything is read. And a session a BNPL purchase already financed is refused as
 * {@code CheckoutSessionNotOpenException}, which {@code confirm} answers with checkout's existing
 * {@code 409 checkout.NotConfirmable} - the losing card or wallet confirmation's answer, never the {@code 500} a missing
 * payment intent would have been. Over real HTTP, with the race behind B3, both are {@code CheckoutFinancingDatabaseTest}'s.
 */
@DisplayName("the confirmation door: BNPL is not a payment confirmation (P12-TSK-007)")
class CheckoutConfirmEndpointTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    @Test
    @DisplayName("instrument \"BNPL\" is the door's existing 422 VALIDATION_FAILED - with or without a payment method"
            + " beside it - while WALLET and a method still parse")
    void theBnplInstrumentIsNotAPaymentConfirmation() {
        for (ConfirmSessionRequest body : List.of(
                new ConfirmSessionRequest(Sensitive.of("a-token"), null, "BNPL"),
                new ConfirmSessionRequest(Sensitive.of("a-token"), UUID.randomUUID(), "BNPL"),
                new ConfirmSessionRequest(Sensitive.of("a-token"), null, "bnpl"))) {
            assertThatThrownBy(() -> CheckoutService.instrumentChoice(body))
                    .isInstanceOfSatisfying(ApiException.class, refused -> {
                        assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED);
                        assertThat(refused.errorCode().status()).isEqualTo(422);
                    });
        }
        assertThat(CheckoutService.instrumentChoice(new ConfirmSessionRequest(Sensitive.of("a-token"), null, "WALLET")))
                .isTrue();
        assertThat(CheckoutService.instrumentChoice(
                new ConfirmSessionRequest(Sensitive.of("a-token"), UUID.randomUUID(), null))).isFalse();
    }

    @Test
    @DisplayName("a session a BNPL purchase financed is refused as not confirmable - the loser's existing answer, never"
            + " a 500 for the missing intent; a session a capture completed still converges")
    void aFinancedSessionIsNotConfirmable() {
        CheckoutSession open = CheckoutSession.open(
                IDS, CLOCK, UUID.randomUUID(), Money.ofMinorUnits(50_00L, CurrencyCode.of("EUR")), "A lamp",
                UUID.randomUUID(), CheckoutSessionToken.issue(new SecureRandom()), Instant.now(CLOCK).plusSeconds(1800));
        CheckoutSession financed = open.completeByFinancing(Instant.now(CLOCK), UUID.randomUUID());

        assertThatThrownBy(() -> CheckoutService.refuseAFinancedSession(financed))
                .isInstanceOfSatisfying(CheckoutSessionNotOpenException.class,
                        refused -> assertThat(refused.status()).isEqualTo(CheckoutSessionStatus.COMPLETED));

        CheckoutSession pending = open.confirm(CLOCK, UUID.randomUUID());
        assertThatCode(() -> CheckoutService.refuseAFinancedSession(pending.complete(CLOCK)))
                .as("a captured session is the paid branch's to converge on").doesNotThrowAnyException();
        assertThatCode(() -> CheckoutService.refuseAFinancedSession(open)).doesNotThrowAnyException();
    }
}
