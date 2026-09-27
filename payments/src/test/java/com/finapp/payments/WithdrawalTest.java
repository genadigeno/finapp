package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.HoldId;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The {@link Withdrawal} aggregate and its machine (`P7-TSK-008`): the exhaustive sweep
 * derived from the machine itself, the coherence refused on rehydrate, the send-permit
 * rules — and {@code INV-REV-03} as a property of the machine's SHAPE: no edge leaves
 * {@code COMPLETED}, asserted from the enum, not a listing.
 */
@DisplayName("Withdrawal (P7-TSK-008)")
class WithdrawalTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Money AMOUNT =
            Money.of(new BigDecimal("25.00"), CurrencyCode.of("EUR"));

    private static Withdrawal dispatched() {
        return Withdrawal.dispatch(
                IDS,
                CLOCK,
                UUID.randomUUID(),
                UUID.randomUUID(),
                LedgerAccountId.of(IDS.next()),
                UUID.randomUUID(),
                new ProviderReference("dest-w-" + IDS.next()),
                AMOUNT,
                RailId.of("instant"),
                HoldId.of(IDS.next()));
    }

    private static Withdrawal inState(WithdrawalStatus status) {
        Withdrawal fresh = dispatched();
        return switch (status) {
            case DISPATCHED -> fresh;
            case UNKNOWN -> fresh.outcomeUnknown();
            case COMPLETED ->
                    fresh.complete(
                            new ProviderReference("sch-" + IDS.next()), Optional.of("C1"));
            case FAILED -> fresh.fail(WithdrawalFailureReason.DECLINED);
        };
    }

    @Nested
    @DisplayName("the machine")
    class TheMachine {

        @Test
        @DisplayName("every transition is enforced, swept from the cross-product")
        void everyTransitionIsEnforced() {
            for (WithdrawalStatus from : WithdrawalStatus.values()) {
                for (WithdrawalStatus to : WithdrawalStatus.values()) {
                    Withdrawal subject = inState(from);
                    Runnable move =
                            switch (to) {
                                case COMPLETED ->
                                        () ->
                                                subject.complete(
                                                        new ProviderReference(
                                                                "sch-" + IDS.next()),
                                                        Optional.empty());
                                case FAILED ->
                                        () ->
                                                subject.fail(
                                                        WithdrawalFailureReason
                                                                .PROVIDER_UNAVAILABLE);
                                case UNKNOWN -> subject::outcomeUnknown;
                                case DISPATCHED -> null; // birth only - no door exists
                            };
                    if (move == null) {
                        continue;
                    }
                    if (from.canTransitionTo(to)) {
                        move.run();
                    } else {
                        assertThatThrownBy(move::run)
                                .isInstanceOf(IllegalWithdrawalTransitionException.class);
                    }
                }
            }
        }

        @Test
        @DisplayName("the generated SQL lists say what the machine says")
        void theGeneratedListsMatchTheMachine() {
            assertThat(WithdrawalStatus.sqlValueList())
                    .isEqualTo("'DISPATCHED', 'COMPLETED', 'FAILED', 'UNKNOWN'");
            assertThat(WithdrawalStatus.resolvableSqlValueList())
                    .isEqualTo("'DISPATCHED', 'UNKNOWN'");
            assertThat(WithdrawalStatus.sqlTerminalValueList())
                    .isEqualTo("'COMPLETED', 'FAILED'");
            assertThat(WithdrawalFailureReason.sqlValueList())
                    .isEqualTo("'DECLINED', 'PROVIDER_UNAVAILABLE', 'NEVER_RECEIVED'");
        }
    }

    @Nested
    @DisplayName("coherence, refused on rehydrate too")
    class Coherence {

        @Test
        @DisplayName("the outcome facts are one fact with the state, both directions")
        void outcomeFactsMatchTheState() {
            Withdrawal base = dispatched();
            // FAILED without a reason, and a reason without FAILED.
            assertThatThrownBy(() -> rehydrated(base, WithdrawalStatus.FAILED, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(
                            () ->
                                    rehydrated(
                                            base,
                                            WithdrawalStatus.DISPATCHED,
                                            WithdrawalFailureReason.DECLINED,
                                            null,
                                            null))
                    .isInstanceOf(IllegalArgumentException.class);
            // COMPLETED without the scheme's reference, and the reference elsewhere.
            assertThatThrownBy(
                            () -> rehydrated(base, WithdrawalStatus.COMPLETED, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(
                            () ->
                                    rehydrated(
                                            base,
                                            WithdrawalStatus.UNKNOWN,
                                            null,
                                            "sch-elsewhere",
                                            null))
                    .isInstanceOf(IllegalArgumentException.class);
            // A settlement cycle rides only an accepted withdrawal.
            assertThatThrownBy(
                            () ->
                                    rehydrated(
                                            base, WithdrawalStatus.UNKNOWN, null, null, "C1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a send permit only moves forward, and only while resolvable")
        void permitRules() {
            Withdrawal live = dispatched();
            assertThatThrownBy(() -> live.withSendPermit(live.lastDispatchedAt().minusSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class);
            Withdrawal renewed = live.withSendPermit(live.lastDispatchedAt().plusSeconds(5));
            assertThat(renewed.lastDispatchedAt())
                    .isEqualTo(live.lastDispatchedAt().plusSeconds(5));
            assertThat(renewed.sendPermitAtOrBefore(renewed.lastDispatchedAt())).isTrue();
            assertThat(renewed.sendPermitAtOrBefore(renewed.lastDispatchedAt().minusNanos(1)))
                    .isFalse();
            Withdrawal done = inState(WithdrawalStatus.COMPLETED);
            assertThatThrownBy(() -> done.withSendPermit(Instant.now(CLOCK).plusSeconds(60)))
                    .as("a resolved withdrawal is never sent again (ADR-0057 §4)")
                    .isInstanceOf(IllegalWithdrawalTransitionException.class);
        }

        private static Withdrawal rehydrated(
                Withdrawal like,
                WithdrawalStatus status,
                WithdrawalFailureReason reason,
                String schemeReference,
                String cycle) {
            return Withdrawal.rehydrate(
                    like.id(),
                    like.partyId(),
                    like.customerId(),
                    like.walletAccountId(),
                    like.paymentMethodId(),
                    like.destination(),
                    like.amount(),
                    like.reference(),
                    like.railId(),
                    status,
                    Optional.ofNullable(reason),
                    Optional.ofNullable(schemeReference).map(ProviderReference::new),
                    Optional.ofNullable(cycle),
                    like.holdId(),
                    like.createdAt(),
                    like.lastDispatchedAt());
        }
    }

    /**
     * Top-level deliberately: `MUTATION_TESTING.md` §2 names this method and the register
     * guard resolves methods on swept simple names, which a nested class is not — the
     * `P7-TSK-007` gate's find, met again here at authoring time.
     */
    @Test
    @DisplayName("the machine is pinned - and NOTHING leaves COMPLETED (INV-REV-03)")
    void theMachineIsPinned() {
        assertThat(WithdrawalStatus.DISPATCHED.permittedTransitions())
                .containsExactlyInAnyOrder(
                        WithdrawalStatus.COMPLETED,
                        WithdrawalStatus.FAILED,
                        WithdrawalStatus.UNKNOWN);
        assertThat(WithdrawalStatus.UNKNOWN.permittedTransitions())
                .containsExactlyInAnyOrder(WithdrawalStatus.COMPLETED, WithdrawalStatus.FAILED);
        // INV-REV-03's domain rank on a final-on-acceptance rail: the accepted
        // withdrawal has no edge at all - not to FAILED, not to anything.
        assertThat(WithdrawalStatus.COMPLETED.permittedTransitions()).isEmpty();
        assertThat(WithdrawalStatus.FAILED.permittedTransitions()).isEmpty();
        // And no state permits a move back to DISPATCHED: dispatch is the only birth.
        for (WithdrawalStatus from : WithdrawalStatus.values()) {
            assertThat(from.canTransitionTo(WithdrawalStatus.DISPATCHED)).isFalse();
        }
    }

    @Test
    @DisplayName("our reference is minted inside ISO 20022's bound, before anything is sent")
    void referenceIsMintedAtBirth() {
        Withdrawal fresh = dispatched();
        assertThat(fresh.reference().value()).matches("[a-f0-9]{32}");
        assertThat(fresh.reference().value().length())
                .isLessThanOrEqualTo(EndToEndReference.MAX_LENGTH);
        assertThat(fresh.lastDispatchedAt()).isEqualTo(fresh.createdAt());
        assertThat(fresh.status()).isEqualTo(WithdrawalStatus.DISPATCHED);
    }

    @Test
    @DisplayName("no rendering carries the amount or a reference (INV-AUD-02)")
    void renderingsCarryIdentifiersOnly() {
        Withdrawal fresh = dispatched();
        assertThat(fresh.toString())
                // The amount's two renderings, not the bare digits: a UUIDv7 identifier is
                // hex and contains "25" one run in a few - the fixture-rank flake class
                // (P7-TSK-004's lesson), found when a rolled id finally collided
                // (P7-TSK-009's gate).
                .doesNotContain("25.00")
                .doesNotContain("2500")
                .doesNotContain(fresh.destination().value())
                .doesNotContain(fresh.reference().value())
                .contains(fresh.id().toString())
                .contains("DISPATCHED");
    }
}
