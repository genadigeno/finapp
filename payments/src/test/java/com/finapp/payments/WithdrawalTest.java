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

    private static Withdrawal held() {
        return Withdrawal.held(
                IDS,
                CLOCK,
                WithdrawalId.next(IDS),
                UUID.randomUUID(),
                UUID.randomUUID(),
                LedgerAccountId.of(IDS.next()),
                UUID.randomUUID(),
                new ProviderReference("dest-w-" + IDS.next()),
                AMOUNT,
                RailId.of("instant"),
                HoldId.of(IDS.next()),
                java.time.Duration.ofHours(72));
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
            case HELD -> held();
        };
    }

    @Nested
    @DisplayName("the machine")
    class TheMachine {

        @Test
        @DisplayName("every transition is enforced, swept from the cross-product - the scheme's"
                + " word and risk's (P13-TSK-009)")
        void everyTransitionIsEnforced() {
            for (WithdrawalStatus from : WithdrawalStatus.values()) {
                for (WithdrawalStatus to : WithdrawalStatus.values()) {
                    Withdrawal subject = inState(from);
                    // The scheme's edges: only after a dispatch.
                    Runnable schemeMove =
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
                                case DISPATCHED ->
                                        () -> subject.release(
                                                subject.lastDispatchedAt().plusSeconds(1),
                                                "risk.action:" + IDS.next());
                                case HELD -> null; // birth only - no edge enters HELD
                            };
                    if (schemeMove == null) {
                        continue;
                    }
                    boolean schemeEdge =
                            from.canTransitionTo(to)
                                    && (from.isResolvable() || to == WithdrawalStatus.DISPATCHED);
                    if (schemeEdge) {
                        schemeMove.run();
                    } else {
                        assertThatThrownBy(schemeMove::run)
                                .as("%s -> %s by the scheme or a release", from, to)
                                .isInstanceOf(IllegalWithdrawalTransitionException.class);
                    }
                }
                // Risk's edges out of HELD (deny, expire): from HELD alone.
                Withdrawal subject = inState(from);
                if (from == WithdrawalStatus.HELD) {
                    assertThat(subject.deny("risk.action:" + IDS.next()).failureReason())
                            .contains(WithdrawalFailureReason.RISK_REFUSED);
                    assertThat(inState(from).expire().failureDetail())
                            .contains(WithdrawalFailureDetail.HOLD_EXPIRED);
                } else {
                    assertThatThrownBy(() -> subject.deny("risk.action:k"))
                            .isInstanceOf(IllegalWithdrawalTransitionException.class);
                    assertThatThrownBy(subject::expire)
                            .isInstanceOf(IllegalWithdrawalTransitionException.class);
                }
            }
        }

        @Test
        @DisplayName("the scheme never fails a held withdrawal, and never with risk's reason"
                + " (P13-TSK-009)")
        void theSchemeNeverRefusesWhatItNeverReceived() {
            assertThatThrownBy(() -> held().fail(WithdrawalFailureReason.DECLINED))
                    .isInstanceOf(IllegalWithdrawalTransitionException.class);
            assertThatThrownBy(() -> dispatched().fail(WithdrawalFailureReason.RISK_REFUSED))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> held().withSendPermit(Instant.now(CLOCK).plusSeconds(60)))
                    .as("a held withdrawal was never sent: no permit is renewed on it")
                    .isInstanceOf(IllegalWithdrawalTransitionException.class);
        }

        @Test
        @DisplayName("the generated SQL lists say what the machine says")
        void theGeneratedListsMatchTheMachine() {
            assertThat(WithdrawalStatus.sqlValueList())
                    .isEqualTo("'DISPATCHED', 'COMPLETED', 'FAILED', 'UNKNOWN', 'HELD'");
            assertThat(WithdrawalStatus.resolvableSqlValueList())
                    .isEqualTo("'DISPATCHED', 'UNKNOWN'");
            assertThat(WithdrawalStatus.sqlTerminalValueList())
                    .isEqualTo("'COMPLETED', 'FAILED'");
            assertThat(WithdrawalFailureReason.sqlValueList())
                    .isEqualTo("'DECLINED', 'PROVIDER_UNAVAILABLE', 'NEVER_RECEIVED', 'RISK_REFUSED'");
            assertThat(WithdrawalFailureDetail.sqlValueList()).isEqualTo("'HOLD_EXPIRED'");
            assertThat(WithdrawalStatus.birthStates())
                    .containsExactly(
                            WithdrawalStatus.DISPATCHED, WithdrawalStatus.FAILED, WithdrawalStatus.HELD);
        }
    }

    @Nested
    @DisplayName("the judged births and the held edges (P13-TSK-009)")
    class TheHeldWithdrawal {

        @Test
        @DisplayName("a held birth carries its window, keeps no reason, and is not resolvable")
        void aHeldBirth() {
            Withdrawal held = held();
            assertThat(held.status()).isEqualTo(WithdrawalStatus.HELD);
            assertThat(held.held()).hasValueSatisfying(window ->
                    assertThat(window.validity()).isEqualTo(java.time.Duration.ofHours(72)));
            assertThat(held.failureReason()).isEmpty();
            assertThat(held.status().isResolvable()).isFalse();
            assertThat(held.status().isTerminal()).isFalse();
        }

        @Test
        @DisplayName("a refused birth is FAILED(RISK_REFUSED) with no window")
        void aRefusedBirth() {
            Withdrawal refused = Withdrawal.refused(IDS, CLOCK, WithdrawalId.next(IDS),
                    UUID.randomUUID(), UUID.randomUUID(), LedgerAccountId.of(IDS.next()),
                    UUID.randomUUID(), new ProviderReference("dest-w-" + IDS.next()), AMOUNT,
                    RailId.of("instant"), HoldId.of(IDS.next()));
            assertThat(refused.status()).isEqualTo(WithdrawalStatus.FAILED);
            assertThat(refused.failureReason()).contains(WithdrawalFailureReason.RISK_REFUSED);
            assertThat(refused.held()).isEmpty();
            assertThat(refused.failureDetail()).isEmpty();
        }

        @Test
        @DisplayName("the release dispatches with a forward permit and its key; the window stays")
        void theRelease() {
            Withdrawal held = held();
            Withdrawal released = held.release(held.lastDispatchedAt().plusSeconds(3), "risk.action:a");
            assertThat(released.status()).isEqualTo(WithdrawalStatus.DISPATCHED);
            assertThat(released.lastDispatchedAt()).isEqualTo(held.lastDispatchedAt().plusSeconds(3));
            assertThat(released.heldResolutionKey()).contains("risk.action:a");
            assertThat(released.held()).isEqualTo(held.held());
            assertThatThrownBy(() -> held.release(held.lastDispatchedAt().minusSeconds(1), "k"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> released.release(released.lastDispatchedAt(), "k"))
                    .isInstanceOf(IllegalWithdrawalTransitionException.class);
        }

        @Test
        @DisplayName("the denial records its key; the expiry its detail and no key")
        void theDenialAndTheExpiry() {
            Withdrawal denied = held().deny("risk.action:d");
            assertThat(denied.heldResolutionKey()).contains("risk.action:d");
            assertThat(denied.failureDetail()).isEmpty();
            Withdrawal expired = held().expire();
            assertThat(expired.failureDetail()).contains(WithdrawalFailureDetail.HOLD_EXPIRED);
            assertThat(expired.heldResolutionKey()).isEmpty();
            assertThat(expired.status().isTerminal()).isTrue();
        }

        @Test
        @DisplayName("V033's coherence is refused on rehydrate too")
        void theHeldCoherence() {
            Withdrawal base = held();
            // HELD without its window.
            assertThatThrownBy(() -> Withdrawal.rehydrate(base.id(), base.partyId(), base.customerId(),
                    base.walletAccountId(), base.paymentMethodId(), base.destination(), base.amount(),
                    base.reference(), base.railId(), WithdrawalStatus.HELD, Optional.empty(),
                    Optional.empty(), Optional.empty(), base.holdId(), base.createdAt(),
                    base.lastDispatchedAt(), Optional.empty(), Optional.empty(), Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
            // A detail beside a scheme reason.
            assertThatThrownBy(() -> Withdrawal.rehydrate(base.id(), base.partyId(), base.customerId(),
                    base.walletAccountId(), base.paymentMethodId(), base.destination(), base.amount(),
                    base.reference(), base.railId(), WithdrawalStatus.FAILED,
                    Optional.of(WithdrawalFailureReason.DECLINED), Optional.empty(), Optional.empty(),
                    base.holdId(), base.createdAt(), base.lastDispatchedAt(), base.held(),
                    Optional.of(WithdrawalFailureDetail.HOLD_EXPIRED), Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
            // A door's key on a row never held.
            Withdrawal never = dispatched();
            assertThatThrownBy(() -> Withdrawal.rehydrate(never.id(), never.partyId(), never.customerId(),
                    never.walletAccountId(), never.paymentMethodId(), never.destination(), never.amount(),
                    never.reference(), never.railId(), WithdrawalStatus.DISPATCHED, Optional.empty(),
                    Optional.empty(), Optional.empty(), never.holdId(), never.createdAt(),
                    never.lastDispatchedAt(), Optional.empty(), Optional.empty(), Optional.of("k")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the stored birth replaces the provisional window and moves the permit only forward")
        void theStoredBirth() {
            Withdrawal held = held();
            Withdrawal.HeldWindow stored = new Withdrawal.HeldWindow(
                    held.createdAt().plusMillis(2), held.createdAt().plusMillis(2).plusSeconds(3600));
            Withdrawal kept = held.withStoredBirth(held.createdAt().plusMillis(2), Optional.of(stored));
            assertThat(kept.held()).contains(stored);
            assertThatThrownBy(() -> held.withStoredBirth(held.createdAt(), Optional.empty()))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> dispatched().withStoredBirth(Instant.now(CLOCK), Optional.of(stored)))
                    .isInstanceOf(IllegalStateException.class);
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
                    like.lastDispatchedAt(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
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
        // P13-TSK-009: a held withdrawal leaves only by its release or by risk's refusal.
        assertThat(WithdrawalStatus.HELD.permittedTransitions())
                .containsExactlyInAnyOrder(WithdrawalStatus.DISPATCHED, WithdrawalStatus.FAILED);
        // INV-REV-03's domain rank on a final-on-acceptance rail: the accepted
        // withdrawal has no edge at all - not to FAILED, not to anything.
        assertThat(WithdrawalStatus.COMPLETED.permittedTransitions()).isEmpty();
        assertThat(WithdrawalStatus.FAILED.permittedTransitions()).isEmpty();
        // And no state but HELD reaches DISPATCHED - the release (P13-TSK-009); nothing ever
        // re-enters HELD: it is a birth, as DISPATCHED is.
        for (WithdrawalStatus from : WithdrawalStatus.values()) {
            assertThat(from.canTransitionTo(WithdrawalStatus.DISPATCHED))
                    .isEqualTo(from == WithdrawalStatus.HELD);
            assertThat(from.canTransitionTo(WithdrawalStatus.HELD)).isFalse();
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
                .doesNotContain(fresh.destination().value())
                .doesNotContain(fresh.reference().value())
                .contains(fresh.id().toString())
                .contains("DISPATCHED");
        // The amount's two renderings, not the bare digits: a UUIDv7 identifier is hex and
        // contains "25" one run in a few - the fixture-rank flake class (P7-TSK-004's lesson),
        // found when a rolled id finally collided (P7-TSK-009's gate) - and "2500" one run in
        // thousands (the P7-TSK-008 needle class). So they are judged against what is left
        // once the identifier is taken out.
        assertThat(fresh.toString().replace(fresh.id().value().toString(), "<withdrawal>"))
                .doesNotContain("25.00")
                .doesNotContain("2500");
    }
}
