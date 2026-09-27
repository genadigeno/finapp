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
 * The {@link Dispute} aggregate (`P7-TSK-012`): born only at an entry stage, moved only along
 * an edge from EVERY stage to EVERY stage ({@code INV-LIFE-02}), terminal stages refusing
 * everything ({@code INV-LIFE-04}), and the coherence refused on rehydrate.
 */
@DisplayName("Dispute (P7-TSK-012)")
class DisputeTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Money AMOUNT =
            Money.of(new BigDecimal("25.00"), CurrencyCode.of("EUR"));

    private static Dispute openedAt(DisputeStage entry) {
        return Dispute.open(
                IDS,
                Instant.now(CLOCK),
                "simulated-card",
                new ProviderReference("dp_" + IDS.next()),
                PaymentAttemptId.of(IDS.next()),
                DisputeReason.FRAUD,
                entry,
                entry.isChargedBack() ? Optional.of(AMOUNT) : Optional.empty());
    }

    /** A dispute standing at {@code stage}, reached the way a notification would reach it. */
    private static Dispute inStage(DisputeStage stage) {
        List<DisputeStage> walk = DisputeStage.openingPathTo(stage);
        Dispute dispute = openedAt(walk.get(0));
        for (DisputeStage next : walk.subList(1, walk.size())) {
            dispute = dispute.advanceTo(next, AMOUNT);
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
                    Dispute moved = dispute.advanceTo(to, AMOUNT);
                    assertThat(moved.stage()).isEqualTo(to);
                    // One edge moves the stage - and the chargeback only on the edge that
                    // enters CHARGED_BACK - and nothing else.
                    assertThat(moved.id()).isEqualTo(dispute.id());
                    assertThat(moved.chargeback().isPresent()).isEqualTo(to.isChargedBack());
                    assertThat(moved.attemptId()).isEqualTo(dispute.attemptId());
                    assertThat(moved.reason()).isEqualTo(dispute.reason());
                    assertThat(moved.openedAt()).isEqualTo(dispute.openedAt());
                } else {
                    assertThatThrownBy(() -> dispute.advanceTo(to, AMOUNT))
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
                assertThatThrownBy(() -> resolved.advanceTo(to, AMOUNT))
                        .isInstanceOf(IllegalDisputeTransitionException.class);
            }
        }
    }

    @Test
    @DisplayName("the coherence V020 holds is held on rehydrate too")
    void aCorruptRowIsRefused() {
        assertThatThrownBy(
                        () ->
                                Dispute.rehydrate(
                                        DisputeId.of(IDS.next()),
                                        "Simulated Card",
                                        new ProviderReference("dp_1"),
                                        PaymentAttemptId.of(IDS.next()),
                                        DisputeReason.FRAUD,
                                        DisputeStage.WON,
                                        Optional.of(AMOUNT),
                                        Instant.now(CLOCK)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider name");
        assertThatThrownBy(
                        () ->
                                Dispute.rehydrate(
                                        DisputeId.of(IDS.next()),
                                        "simulated-card",
                                        new ProviderReference("dp_1"),
                                        PaymentAttemptId.of(IDS.next()),
                                        DisputeReason.FRAUD,
                                        DisputeStage.CHARGED_BACK,
                                        Optional.of(Money.ofMinorUnits(0, CurrencyCode.of("EUR"))),
                                        Instant.now(CLOCK)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        // The chargeback's presence IS the stage's: none at an inquiry, one once charged back.
        for (DisputeStage stage : DisputeStage.values()) {
            Optional<Money> wrong = stage.isChargedBack() ? Optional.empty() : Optional.of(AMOUNT);
            assertThatThrownBy(
                            () ->
                                    Dispute.rehydrate(
                                            DisputeId.of(IDS.next()),
                                            "simulated-card",
                                            new ProviderReference("dp_1"),
                                            PaymentAttemptId.of(IDS.next()),
                                            DisputeReason.FRAUD,
                                            stage,
                                            wrong,
                                            Instant.now(CLOCK)))
                    .as("%s with the wrong chargeback presence", stage)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("chargeback amount");
        }
    }

    @Test
    @DisplayName("the chargeback's amount ARRIVES with the chargeback - on an inquiry's escalation"
            + " as at birth - and a recorded one never moves on a later edge")
    void theChargebackArrivesWithTheChargeback() {
        Money partial = Money.of(new BigDecimal("8.00"), CurrencyCode.of("EUR"));
        Dispute inquiry = openedAt(DisputeStage.INQUIRY);
        assertThat(inquiry.chargeback()).as("an inquiry has taken nothing").isEmpty();

        Dispute escalated = inquiry.advanceTo(DisputeStage.CHARGED_BACK, partial);
        assertThat(escalated.chargeback()).contains(partial);

        Dispute represented =
                escalated.advanceTo(
                        DisputeStage.REPRESENTED, Money.of(new BigDecimal("9.99"),
                                CurrencyCode.of("EUR")));
        assertThat(represented.chargeback())
                .as("a later edge's stated figure never revises what was taken")
                .contains(partial);
        assertThat(inquiry.advanceTo(DisputeStage.CLOSED, AMOUNT).chargeback())
                .as("an inquiry closed without a chargeback took nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("INV-AUD-02: the dispute and the notice print identifiers and names, never the"
            + " amount or the network's reference")
    void neitherPrintsTheAmountOrTheReference() {
        Dispute dispute = openedAt(DisputeStage.CHARGED_BACK);
        // Needles carry the decimal point: a bare digit run can occur inside a UUIDv7's hex
        // (the P7-TSK-008 needle class), a '.' never can.
        assertThat(dispute.toString())
                .doesNotContain("25.00")
                .doesNotContain(dispute.providerReference().value());
        DisputeNotice notice =
                new DisputeNotice(
                        "simulated-card",
                        new ProviderReference("dp_needle_7"),
                        DisputeStage.WON,
                        DisputeReason.CONSUMER_DISPUTE,
                        Money.of(new BigDecimal("98.76"), CurrencyCode.of("EUR")));
        assertThat(notice.toString()).doesNotContain("98.76").doesNotContain("dp_needle_7");
        assertThatThrownBy(
                        () ->
                                new DisputeNotice(
                                        "simulated-card",
                                        new ProviderReference("dp_1"),
                                        DisputeStage.WON,
                                        DisputeReason.FRAUD,
                                        Money.ofMinorUnits(-5, CurrencyCode.of("EUR"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
