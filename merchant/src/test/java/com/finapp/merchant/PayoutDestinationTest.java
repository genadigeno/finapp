package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The payout destination machine and coherence (`P6-TSK-011`, {@code INV-AUD-04},
 * {@code INV-LIFE-01/-02/-04}): the exhaustive cross-product sweep derived from
 * {@code permittedTransitions()}, the four-eyes refusal, the cooling-off, and the constructor's
 * refusals of every corrupt row. The schema half is {@code PayoutDestinationMigrationTest}; the
 * every-writer half is the app database suite against `V006`.
 */
@DisplayName("the PayoutDestination aggregate (P6-TSK-011)")
class PayoutDestinationTest {

    private static final Instant T0 = Instant.parse("2026-09-23T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final Duration COOLING_OFF = Duration.ofHours(72);
    /** A clock past the cooling-off. */
    private static final Clock LATER = Clock.fixed(T0.plus(COOLING_OFF).plusSeconds(1), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final MerchantId MERCHANT = MerchantId.next(IDS);
    private static final PayoutDestinationReference REFERENCE =
            PayoutDestinationReference.of("pdr_4f9ZkQ2mX");

    private static final String PROPOSER = "operator-proposer";
    private static final String APPROVER = "operator-approver";

    @Test
    @DisplayName("every illegal edge is refused, swept from the machine's own cross-product")
    void everyIllegalEdgeIsRefused() {
        for (PayoutDestinationStatus from : PayoutDestinationStatus.values()) {
            for (PayoutDestinationStatus to : PayoutDestinationStatus.values()) {
                if (to == PayoutDestinationStatus.PROPOSED) {
                    continue; // birth, not an edge: no method drives into PROPOSED
                }
                PayoutDestination subject = at(from);
                boolean legal = from.canTransitionTo(to);
                Supplier<PayoutDestination> move =
                        switch (to) {
                            case APPROVED -> () -> subject.approve(APPROVER, CLOCK, COOLING_OFF);
                            case REJECTED -> () -> subject.reject(APPROVER, CLOCK);
                            case WITHDRAWN -> () -> subject.withdraw(PROPOSER, CLOCK);
                            case EFFECTIVE -> () -> subject.effect(LATER);
                            case SUPERSEDED -> () -> subject.supersede(LATER);
                            case PROPOSED -> throw new AssertionError("skipped above");
                        };
                if (legal) {
                    assertThat(move.get().status()).as("%s -> %s", from, to).isEqualTo(to);
                } else {
                    assertThatExceptionOfType(IllegalPayoutDestinationTransitionException.class)
                            .as("%s -> %s must be refused", from, to)
                            .isThrownBy(move::get);
                }
            }
        }
    }

    @Test
    @DisplayName("the proposer cannot approve their own destination (INV-AUD-04)")
    void theProposerCannotApprove() {
        PayoutDestination proposed = proposed();
        assertThatExceptionOfType(PayoutDestinationSelfApprovalException.class)
                .isThrownBy(() -> proposed.approve(PROPOSER, CLOCK, COOLING_OFF));
        assertThat(proposed.status()).isEqualTo(PayoutDestinationStatus.PROPOSED);
        assertThat(proposed.approvedBy()).isEmpty();
    }

    @Test
    @DisplayName("approval pins the cooling-off deadline on the row")
    void approvalPinsTheDeadline() {
        PayoutDestination approved = proposed().approve(APPROVER, CLOCK, COOLING_OFF);
        assertThat(approved.approvedBy()).contains(APPROVER);
        assertThat(approved.approvedAt()).contains(T0);
        assertThat(approved.coolingOffUntil()).contains(T0.plus(COOLING_OFF));
    }

    @Test
    @DisplayName("a destination is due exactly from its deadline, and not a moment before")
    void dueExactlyFromTheDeadline() {
        PayoutDestination approved = proposed().approve(APPROVER, CLOCK, COOLING_OFF);
        Instant deadline = T0.plus(COOLING_OFF);
        assertThat(approved.isDue(deadline.minusNanos(1))).isFalse();
        assertThat(approved.isDue(deadline)).isTrue();
        assertThat(proposed().isDue(deadline.plusSeconds(1)))
                .as("only an APPROVED change is ever due")
                .isFalse();
    }

    @Test
    @DisplayName("effect during the cooling-off is refused")
    void effectDuringTheCoolingOffIsRefused() {
        PayoutDestination approved = proposed().approve(APPROVER, CLOCK, COOLING_OFF);
        Clock oneSecondEarly = Clock.fixed(T0.plus(COOLING_OFF).minusSeconds(1), ZoneOffset.UTC);
        assertThatIllegalStateException().isThrownBy(() -> approved.effect(oneSecondEarly));
        assertThat(approved.effect(LATER).status()).isEqualTo(PayoutDestinationStatus.EFFECTIVE);
    }

    @Test
    @DisplayName("a non-positive cooling-off is refused: zero is the control switched off")
    void aNonPositiveCoolingOffIsRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> proposed().approve(APPROVER, CLOCK, Duration.ZERO));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> proposed().approve(APPROVER, CLOCK, Duration.ofSeconds(-1)));
    }

    @Test
    @DisplayName("withdrawal during the cooling-off keeps the approval and records the end")
    void withdrawalDuringTheCoolingOff() {
        PayoutDestination withdrawn =
                proposed().approve(APPROVER, CLOCK, COOLING_OFF).withdraw(PROPOSER, CLOCK);
        assertThat(withdrawn.status()).isEqualTo(PayoutDestinationStatus.WITHDRAWN);
        assertThat(withdrawn.approvedBy()).contains(APPROVER);
        assertThat(withdrawn.endedBy()).contains(PROPOSER);
        assertThat(withdrawn.endedAt()).contains(T0);
        assertThat(withdrawn.isDue(LATER.instant())).isFalse();
    }

    @Test
    @DisplayName("supersession is never recorded before the destination took effect")
    void supersessionNeverPrecedesTheEffect() {
        PayoutDestination effective = proposed().approve(APPROVER, CLOCK, COOLING_OFF).effect(LATER);
        // A second instance whose clock runs behind the one that effected it.
        PayoutDestination superseded = effective.supersede(CLOCK);
        assertThat(superseded.supersededAt()).isEqualTo(effective.effectiveAt());
    }

    @Test
    @DisplayName("the constructor holds the coherence: a corrupt row is refused at read")
    void corruptRowsAreRefusedAtRead() {
        Optional<Instant> deadline = Optional.of(T0.plus(COOLING_OFF));
        // APPROVED without its approval.
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                row(
                                        PayoutDestinationStatus.APPROVED,
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()));
        // A stored self-approval.
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                row(
                                        PayoutDestinationStatus.APPROVED,
                                        Optional.of(PROPOSER),
                                        Optional.of(T0),
                                        deadline,
                                        Optional.empty(),
                                        Optional.empty()));
        // PROPOSED carrying an approval.
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                row(
                                        PayoutDestinationStatus.PROPOSED,
                                        Optional.of(APPROVER),
                                        Optional.of(T0),
                                        deadline,
                                        Optional.empty(),
                                        Optional.empty()));
        // EFFECTIVE before its cooling-off ended.
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                row(
                                        PayoutDestinationStatus.EFFECTIVE,
                                        Optional.of(APPROVER),
                                        Optional.of(T0),
                                        deadline,
                                        Optional.of(T0.plusSeconds(60)),
                                        Optional.empty()));
        // A cooling-off that ends at the approval, not after it.
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                row(
                                        PayoutDestinationStatus.APPROVED,
                                        Optional.of(APPROVER),
                                        Optional.of(T0),
                                        Optional.of(T0),
                                        Optional.empty(),
                                        Optional.empty()));
        // REJECTED without who rejected it.
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                row(
                                        PayoutDestinationStatus.REJECTED,
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()));
        // And the coherent rows construct.
        assertThat(
                        row(
                                        PayoutDestinationStatus.EFFECTIVE,
                                        Optional.of(APPROVER),
                                        Optional.of(T0),
                                        deadline,
                                        deadline,
                                        Optional.empty())
                                .status())
                .isEqualTo(PayoutDestinationStatus.EFFECTIVE);
    }

    @Test
    @DisplayName("the proposal's fields are bounded: suffix shape, reason, actor")
    void theProposalIsBounded() {
        assertThatIllegalArgumentException().isThrownBy(() -> proposeWith("12345", "reason"));
        assertThatIllegalArgumentException().isThrownBy(() -> proposeWith("ab12", "reason"));
        assertThatIllegalArgumentException().isThrownBy(() -> proposeWith("3000", " "));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> proposeWith("3000", "x".repeat(PayoutDestination.MAX_REASON_LENGTH + 1)));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                PayoutDestination.propose(
                                        IDS, CLOCK, MERCHANT, REFERENCE, "3000", "", "reason"));
        assertThat(proposeWith("30A0", "a reason").displaySuffix()).isEqualTo("30A0");
    }

    // -----------------------------------------------------------------

    private static PayoutDestination proposed() {
        return PayoutDestination.propose(
                IDS, CLOCK, MERCHANT, REFERENCE, "3000", PROPOSER, "the merchant's new account");
    }

    private static PayoutDestination proposeWith(String suffix, String reason) {
        return PayoutDestination.propose(IDS, CLOCK, MERCHANT, REFERENCE, suffix, PROPOSER, reason);
    }

    /** Drives the machine to {@code status} along its own edges. */
    private static PayoutDestination at(PayoutDestinationStatus status) {
        PayoutDestination proposed = proposed();
        return switch (status) {
            case PROPOSED -> proposed;
            case APPROVED -> proposed.approve(APPROVER, CLOCK, COOLING_OFF);
            case EFFECTIVE -> proposed.approve(APPROVER, CLOCK, COOLING_OFF).effect(LATER);
            case SUPERSEDED ->
                    proposed.approve(APPROVER, CLOCK, COOLING_OFF).effect(LATER).supersede(LATER);
            case REJECTED -> proposed.reject(APPROVER, CLOCK);
            case WITHDRAWN -> proposed.withdraw(PROPOSER, CLOCK);
        };
    }

    private static PayoutDestination row(
            PayoutDestinationStatus status,
            Optional<String> approvedBy,
            Optional<Instant> approvedAt,
            Optional<Instant> coolingOffUntil,
            Optional<Instant> effectiveAt,
            Optional<Instant> supersededAt) {
        return PayoutDestination.rehydrate(
                PayoutDestinationId.next(IDS),
                MERCHANT,
                REFERENCE,
                "3000",
                status,
                PROPOSER,
                T0,
                "the merchant's new account",
                approvedBy,
                approvedAt,
                coolingOffUntil,
                effectiveAt,
                supersededAt,
                Optional.empty(),
                Optional.empty());
    }
}
