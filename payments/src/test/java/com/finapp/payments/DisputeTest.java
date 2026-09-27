package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@link Dispute} aggregate (`P7-TSK-012`; its attribution and fee `P7-TSK-013`): born only
 * at an entry stage, moved only along an edge from EVERY stage to EVERY stage
 * ({@code INV-LIFE-02}), terminal stages refusing everything ({@code INV-LIFE-04}), the
 * chargeback and its attribution arriving on exactly one edge, the attribution re-attributed only
 * while the chargeback stands, the fee recorded once, and the coherence refused on rehydrate.
 */
@DisplayName("Dispute (P7-TSK-012, P7-TSK-013)")
class DisputeTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Money AMOUNT = Money.of(new BigDecimal("25.00"), EUR);

    /** A chargeback the counterparty bears in full - the common case. */
    private static Optional<ChargebackSplit> taken(Money amount) {
        return Optional.of(ChargebackSplit.of(amount, amount, true));
    }

    private static Dispute openedAt(DisputeStage entry) {
        return Dispute.open(
                IDS,
                Instant.now(CLOCK),
                "simulated-card",
                new ProviderReference("dp_" + IDS.next()),
                PaymentAttemptId.of(IDS.next()),
                DisputeReason.FRAUD,
                entry,
                entry.isChargedBack() ? taken(AMOUNT) : Optional.empty(),
                Optional.empty());
    }

    /** The arrival an edge carries: the chargeback on the one edge that enters it, else none. */
    private static Optional<ChargebackSplit> arriving(DisputeStage next) {
        return next == DisputeStage.CHARGED_BACK ? taken(AMOUNT) : Optional.empty();
    }

    /** A dispute standing at {@code stage}, reached the way a notification would reach it. */
    private static Dispute inStage(DisputeStage stage) {
        List<DisputeStage> walk = DisputeStage.openingPathTo(stage);
        Dispute dispute = openedAt(walk.get(0));
        for (DisputeStage next : walk.subList(1, walk.size())) {
            dispute = dispute.advanceTo(next, arriving(next));
        }
        return dispute;
    }

    @Test
    @DisplayName("born at an entry stage and refused at every other")
    void bornOnlyAtAnEntry() {
        for (DisputeStage stage : DisputeStage.values()) {
            if (stage.isEntry()) {
                assertThat(openedAt(stage).stage()).isEqualTo(stage);
            } else {
                assertThatThrownBy(() -> openedAt(stage))
                        .isInstanceOf(IllegalDisputeTransitionException.class)
                        .hasMessageContaining("born at " + stage);
            }
        }
    }

    @Test
    @DisplayName("INV-LIFE-02 from EVERY stage to EVERY stage: an edge moves, anything else is"
            + " refused and the dispute is unchanged")
    void everyEdgeMovesAndEveryOtherIsRefused() {
        for (DisputeStage from : DisputeStage.values()) {
            for (DisputeStage to : DisputeStage.values()) {
                Dispute dispute = inStage(from);
                if (from.canTransitionTo(to)) {
                    Dispute moved = dispute.advanceTo(to, arriving(to));
                    assertThat(moved.stage()).isEqualTo(to);
                    // One edge moves the stage - and the chargeback only on the edge that
                    // enters CHARGED_BACK - and nothing else.
                    assertThat(moved.id()).isEqualTo(dispute.id());
                    assertThat(moved.chargeback().isPresent()).isEqualTo(to.isChargedBack());
                    assertThat(moved.split().isPresent()).isEqualTo(to.isChargedBack());
                    assertThat(moved.attemptId()).isEqualTo(dispute.attemptId());
                    assertThat(moved.reason()).isEqualTo(dispute.reason());
                    assertThat(moved.openedAt()).isEqualTo(dispute.openedAt());
                } else {
                    assertThatThrownBy(() -> dispute.advanceTo(to, arriving(to)))
                            .as("%s -> %s", from, to)
                            .isInstanceOf(IllegalDisputeTransitionException.class);
                    assertThat(dispute.stage()).isEqualTo(from);
                }
            }
        }
    }

    @Test
    @DisplayName("INV-LIFE-04: each terminal stage refuses every move, swept separately")
    void everyTerminalRefusesEverything() {
        for (DisputeStage terminal : DisputeStage.values()) {
            if (!terminal.isTerminal()) {
                continue;
            }
            Dispute resolved = inStage(terminal);
            for (DisputeStage to : DisputeStage.values()) {
                assertThatThrownBy(() -> resolved.advanceTo(to, arriving(to)))
                        .isInstanceOf(IllegalDisputeTransitionException.class);
            }
        }
    }

    @Test
    @DisplayName("the coherence V020 and V021 hold is held on rehydrate too")
    void aCorruptRowIsRefused() {
        assertThatThrownBy(() -> rehydrated("Simulated Card", DisputeStage.WON, taken(AMOUNT),
                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider name");
        // The chargeback's presence IS the stage's: none at an inquiry, one once charged back.
        for (DisputeStage stage : DisputeStage.values()) {
            Optional<ChargebackSplit> wrong =
                    stage.isChargedBack() ? Optional.empty() : taken(AMOUNT);
            assertThatThrownBy(() -> rehydrated("simulated-card", stage, wrong, Optional.empty()))
                    .as("%s with the wrong chargeback presence", stage)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("chargeback is recorded exactly");
        }
        // A fee rides the chargeback: never on an inquiry, never another currency, positive.
        assertThatThrownBy(() -> rehydrated("simulated-card", DisputeStage.INQUIRY,
                        Optional.empty(), Optional.of(Money.ofMinorUnits(1500, EUR))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never on an inquiry");
        assertThatThrownBy(() -> rehydrated("simulated-card", DisputeStage.CHARGED_BACK,
                        taken(AMOUNT),
                        Optional.of(Money.ofMinorUnits(1500, CurrencyCode.of("GBP")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chargeback's currency");
    }

    @Test
    @DisplayName("the chargeback and its attribution ARRIVE with the chargeback - on an inquiry's"
            + " escalation as at birth - and are refused on every other edge")
    void theChargebackArrivesWithTheChargeback() {
        Money partial = Money.of(new BigDecimal("8.00"), EUR);
        Dispute inquiry = openedAt(DisputeStage.INQUIRY);
        assertThat(inquiry.chargeback()).as("an inquiry has taken nothing").isEmpty();

        Dispute escalated = inquiry.advanceTo(DisputeStage.CHARGED_BACK, taken(partial));
        assertThat(escalated.chargeback()).contains(partial);

        Dispute represented = escalated.advanceTo(DisputeStage.REPRESENTED, Optional.empty());
        assertThat(represented.chargeback())
                .as("a later edge never revises what was taken")
                .contains(partial);
        assertThatThrownBy(
                        () ->
                                escalated.advanceTo(
                                        DisputeStage.REPRESENTED,
                                        taken(Money.of(new BigDecimal("9.99"), EUR))))
                .as("a second chargeback on a later edge is refused, not absorbed")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inquiry.advanceTo(DisputeStage.CHARGED_BACK, Optional.empty()))
                .as("entering CHARGED_BACK without what was taken is refused")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(inquiry.advanceTo(DisputeStage.CLOSED, Optional.empty()).chargeback())
                .as("an inquiry closed without a chargeback took nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("INV-DSP-01: only a standing chargeback's excess is ever re-attributed - never a"
            + " won one, never an inquiry, never more than the excess")
    void onlyAStandingExcessIsReattributed() {
        Money taken = Money.ofMinorUnits(1000, EUR);
        Money four = Money.ofMinorUnits(400, EUR);
        Dispute charged =
                Dispute.open(
                        IDS,
                        Instant.now(CLOCK),
                        "simulated-card",
                        new ProviderReference("dp_" + IDS.next()),
                        PaymentAttemptId.of(IDS.next()),
                        DisputeReason.FRAUD,
                        DisputeStage.CHARGED_BACK,
                        // 4.00 of headroom: 6.00 is the excess.
                        Optional.of(ChargebackSplit.of(taken, four, true)),
                        Optional.empty());
        Dispute moved = charged.reattributed(Money.ofMinorUnits(250, EUR), true);
        assertThat(moved.split().orElseThrow().counterpartyShare())
                .isEqualTo(Money.ofMinorUnits(650, EUR));
        assertThat(moved.split().orElseThrow().excess()).isEqualTo(Money.ofMinorUnits(350, EUR));
        assertThat(moved.stage()).as("a re-attribution never moves the stage")
                .isEqualTo(DisputeStage.CHARGED_BACK);
        assertThatThrownBy(() -> charged.reattributed(Money.ofMinorUnits(601, EUR), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most the excess");

        Dispute lost = charged.advanceTo(DisputeStage.LOST, Optional.empty());
        assertThat(lost.reattributed(Money.ofMinorUnits(100, EUR), false).split().orElseThrow()
                        .parkedShare())
                .as("a lost chargeback still stands: its written-off excess can come back")
                .isEqualTo(Money.ofMinorUnits(100, EUR));
        Dispute won =
                charged.advanceTo(DisputeStage.REPRESENTED, Optional.empty())
                        .advanceTo(DisputeStage.WON, Optional.empty());
        assertThatThrownBy(() -> won.reattributed(Money.ofMinorUnits(100, EUR), true))
                .as("a won chargeback's attribution was reversed with the funds")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () -> openedAt(DisputeStage.INQUIRY)
                                .reattributed(Money.ofMinorUnits(100, EUR), true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the PSP's dispute fee is recorded once, only on a charged-back dispute")
    void theFeeIsRecordedOnce() {
        Money fee = Money.ofMinorUnits(1500, EUR);
        Dispute charged = openedAt(DisputeStage.CHARGED_BACK);
        Dispute feed = charged.withFee(fee);
        assertThat(feed.fee()).contains(fee);
        assertThatThrownBy(() -> feed.withFee(Money.ofMinorUnits(1600, EUR)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never changes");
        assertThatThrownBy(() -> openedAt(DisputeStage.INQUIRY).withFee(fee))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never on an inquiry");
        assertThat(feed.advanceTo(DisputeStage.LOST, Optional.empty()).fee())
                .as("a later edge carries the fee forward untouched")
                .contains(fee);
    }

    @Test
    @DisplayName("INV-AUD-02: the dispute, the split and the notice print identifiers and names,"
            + " never an amount or the network's reference")
    void noneOfThemPrintsAnAmountOrTheReference() {
        Dispute dispute = openedAt(DisputeStage.CHARGED_BACK);
        // Needles carry the decimal point: a bare digit run can occur inside a UUIDv7's hex
        // (the P7-TSK-008 needle class), a '.' never can.
        assertThat(dispute.toString())
                .doesNotContain("25.00")
                .doesNotContain(dispute.providerReference().value());
        assertThat(dispute.split().orElseThrow().toString()).doesNotContain("25.00");
        DisputeNotice notice =
                new DisputeNotice(
                        "simulated-card",
                        new ProviderReference("dp_needle_7"),
                        DisputeStage.WON,
                        DisputeReason.CONSUMER_DISPUTE,
                        Money.of(new BigDecimal("98.76"), EUR),
                        Optional.of(Money.of(new BigDecimal("15.43"), EUR)));
        assertThat(notice.toString())
                .doesNotContain("98.76")
                .doesNotContain("15.43")
                .doesNotContain("dp_needle_7");
        assertThatThrownBy(
                        () ->
                                new DisputeNotice(
                                        "simulated-card",
                                        new ProviderReference("dp_1"),
                                        DisputeStage.WON,
                                        DisputeReason.FRAUD,
                                        Money.ofMinorUnits(-5, EUR)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new DisputeNotice(
                                        "simulated-card",
                                        new ProviderReference("dp_1"),
                                        DisputeStage.INQUIRY,
                                        DisputeReason.FRAUD,
                                        AMOUNT,
                                        Optional.of(Money.ofMinorUnits(1500, EUR))))
                .as("a fee is charged with the funds taken, never on an inquiry")
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Dispute rehydrated(
            String provider,
            DisputeStage stage,
            Optional<ChargebackSplit> chargeback,
            Optional<Money> fee) {
        return Dispute.rehydrate(
                DisputeId.of(IDS.next()),
                provider,
                new ProviderReference("dp_1"),
                PaymentAttemptId.of(IDS.next()),
                DisputeReason.FRAUD,
                stage,
                chargeback,
                fee,
                Optional.empty(),
                Instant.now(CLOCK));
    }
}
