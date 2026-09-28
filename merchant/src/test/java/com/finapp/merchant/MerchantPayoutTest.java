package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.HoldId;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The payout aggregate (`P6-TSK-012`): born {@code DISPATCHED}, every illegal edge refused from
 * the machine's own cross-product, the coherence `V007` holds held here too, and the send permit
 * that makes {@code NEVER_RECEIVED} a safe conclusion.
 */
@DisplayName("the MerchantPayout aggregate (P6-TSK-012)")
class MerchantPayoutTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Actor MERCHANT =
            new Actor(UUID.randomUUID().toString(), ActorType.MERCHANT);
    private static final Actor OPERATOR =
            new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);

    @Test
    @DisplayName("born DISPATCHED, with our minted reference and its first send permit at birth")
    void bornDispatched() {
        MerchantPayout payout = dispatched();
        assertThat(payout.status()).isEqualTo(MerchantPayoutStatus.DISPATCHED);
        assertThat(payout.reference().value()).startsWith("pyo-");
        assertThat(payout.lastDispatchedAt()).isEqualTo(payout.createdAt());
        assertThat(payout.failureReason()).isEmpty();
        assertThat(payout.providerReference()).isEmpty();
    }

    @Test
    @DisplayName("every illegal edge is refused, swept from the machine's own cross-product")
    void everyIllegalEdgeIsRefused() {
        for (MerchantPayoutStatus from : MerchantPayoutStatus.values()) {
            MerchantPayout at = in(from);
            for (MerchantPayoutStatus to : MerchantPayoutStatus.values()) {
                if (from.canTransitionTo(to)) {
                    assertThat(move(at, to).status()).isEqualTo(to);
                } else {
                    assertThatThrownBy(() -> move(at, to))
                            .as("%s -> %s", from, to)
                            .isInstanceOf(IllegalMerchantPayoutTransitionException.class);
                }
            }
        }
    }

    @Test
    @DisplayName("a failure reason exactly when FAILED, the provider's reference exactly when COMPLETED")
    void theOutcomeFieldsFollowTheState() {
        MerchantPayout failed = dispatched().fail(PayoutFailureReason.DECLINED);
        assertThat(failed.failureReason()).contains(PayoutFailureReason.DECLINED);
        assertThat(failed.providerReference()).isEmpty();
        MerchantPayout completed = dispatched().complete(new PayoutProviderReference("po_1"));
        assertThat(completed.providerReference()).contains(new PayoutProviderReference("po_1"));
        assertThat(completed.failureReason()).isEmpty();

        // And a stored row that disagrees is refused at read.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> rehydrated(MerchantPayoutStatus.FAILED, Optional.empty(),
                        Optional.empty(), Optional.empty(), ActorType.MERCHANT));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> rehydrated(MerchantPayoutStatus.COMPLETED, Optional.empty(),
                        Optional.empty(), Optional.empty(), ActorType.MERCHANT));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> rehydrated(MerchantPayoutStatus.DISPATCHED, Optional.empty(),
                        Optional.of(new PayoutProviderReference("po_2")), Optional.empty(),
                        ActorType.MERCHANT));
    }

    @Test
    @DisplayName("the merchant's own payout carries no reason, and an operator's always does")
    void theReasonFollowsTheRequester() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> dispatch(MERCHANT, Optional.of("because")));
        assertThatIllegalArgumentException().isThrownBy(() -> dispatch(OPERATOR, Optional.empty()));
        assertThat(dispatch(OPERATOR, Optional.of("support ticket 42")).reason())
                .contains("support ticket 42");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> dispatch(OPERATOR, Optional.of("x".repeat(1001))));
    }

    @Test
    @DisplayName("an amount must be positive")
    void aPositiveAmount() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                MerchantPayout.dispatch(
                                        IDS, CLOCK, MerchantId.next(IDS),
                                        Money.ofMinorUnits(0, EUR),
                                        PayoutDestinationId.next(IDS), HoldId.next(IDS),
                                        MERCHANT, Optional.empty()));
    }

    @Test
    @DisplayName("the send permit moves forward only, and only while the payout is resolvable")
    void theSendPermit() {
        MerchantPayout payout = dispatched();
        Instant later = payout.createdAt().plus(Duration.ofMinutes(5));
        assertThat(payout.withSendPermit(later).lastDispatchedAt()).isEqualTo(later);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> payout.withSendPermit(payout.createdAt().minusSeconds(1)));
        assertThat(payout.outcomeUnknown().withSendPermit(later).lastDispatchedAt())
                .as("an UNKNOWN payout may be re-sent: our reference is idempotent at the rail")
                .isEqualTo(later);
        assertThatThrownBy(() -> payout.fail(PayoutFailureReason.DECLINED).withSendPermit(later))
                .as("a resolved payout is never sent again")
                .isInstanceOf(IllegalMerchantPayoutTransitionException.class);
        assertThatIllegalArgumentException()
                .as("and a stored permit before the birth is refused at read")
                .isThrownBy(
                        () ->
                                MerchantPayout.rehydrate(
                                        payout.id(), payout.merchantId(), payout.amount(),
                                        payout.destinationId(), payout.holdId(),
                                        payout.reference(), payout.status(), Optional.empty(),
                                        Optional.empty(), payout.requestedBy(),
                                        payout.requestedByType(), Optional.empty(),
                                        payout.createdAt(), payout.createdAt().minusSeconds(1)));
    }

    @Test
    @DisplayName("never-received is safe only at or before the bound: a younger permit may be in flight")
    void theNeverReceivedBound() {
        MerchantPayout payout = dispatched();
        assertThat(payout.sendPermitAtOrBefore(payout.lastDispatchedAt())).isTrue();
        assertThat(payout.sendPermitAtOrBefore(payout.lastDispatchedAt().plusSeconds(1))).isTrue();
        assertThat(payout.sendPermitAtOrBefore(payout.lastDispatchedAt().minusSeconds(1)))
                .isFalse();
    }

    @Test
    @DisplayName("toString names identifiers and state, never the amount, reason or destination")
    void toStringLeaksNothing() {
        MerchantPayout payout = dispatch(OPERATOR, Optional.of("a private reason"));
        assertThat(payout.toString())
                .contains(payout.id().toString())
                .doesNotContain("a private reason")
                .doesNotContain(payout.destinationId().value().toString());
        // The payout and its merchant are named on purpose, and a random UUIDv7's hex can carry
        // any digit run (the P7-TSK-008 needle class): the amount is judged against what is
        // left once the identifiers are taken out.
        assertThat(payout.toString()
                        .replace(payout.id().value().toString(), "<payout>")
                        .replace(payout.merchantId().value().toString(), "<merchant>"))
                .doesNotContain("2500");
    }

    private static MerchantPayout dispatched() {
        return dispatch(MERCHANT, Optional.empty());
    }

    private static MerchantPayout dispatch(Actor requester, Optional<String> reason) {
        return MerchantPayout.dispatch(
                IDS,
                CLOCK,
                MerchantId.next(IDS),
                Money.ofMinorUnits(2500, EUR),
                PayoutDestinationId.next(IDS),
                HoldId.next(IDS),
                requester,
                reason);
    }

    private static MerchantPayout in(MerchantPayoutStatus status) {
        return switch (status) {
            case DISPATCHED -> dispatched();
            case UNKNOWN -> dispatched().outcomeUnknown();
            case COMPLETED -> dispatched().complete(new PayoutProviderReference("po_x"));
            case FAILED -> dispatched().fail(PayoutFailureReason.DECLINED);
        };
    }

    private static MerchantPayout move(MerchantPayout from, MerchantPayoutStatus to) {
        return switch (to) {
            case COMPLETED -> from.complete(new PayoutProviderReference("po_y"));
            case FAILED -> from.fail(PayoutFailureReason.DECLINED);
            case UNKNOWN -> from.outcomeUnknown();
            // No aggregate door leads INTO DISPATCHED: it is the birth state only.
            case DISPATCHED ->
                    throw new IllegalMerchantPayoutTransitionException(from.status(), to);
        };
    }

    private static MerchantPayout rehydrated(
            MerchantPayoutStatus status,
            Optional<PayoutFailureReason> why,
            Optional<PayoutProviderReference> theirs,
            Optional<String> reason,
            ActorType requesterType) {
        MerchantPayout born = dispatched();
        return MerchantPayout.rehydrate(
                born.id(),
                born.merchantId(),
                born.amount(),
                born.destinationId(),
                born.holdId(),
                born.reference(),
                status,
                why,
                theirs,
                born.requestedBy(),
                requesterType,
                reason,
                born.createdAt(),
                born.lastDispatchedAt());
    }
}
