package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pinned decision's own coherence (`P7-TSK-003`, `INV-RAIL-02`): the chosen rail is the
 * trail's, the trail is contiguous, at most one chosen step is un-abandoned, and the one
 * append door abandons only on knowledge — a second abandonment, or one with nothing
 * chosen, is refused as the claim of a send that never happened.
 */
@DisplayName("the routing decision (P7-TSK-003)")
class RoutingDecisionTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Money AMOUNT =
            Money.ofMinorUnits(12_34, CurrencyCode.of("EUR"));
    private static final RailId CARD = SimulatedCardPspAdapter.RAIL.id();

    @Test
    @DisplayName("a step is coherent or refused: rejection exactly when not chosen, abandoned"
            + " only on NOTHING_SENT, the descriptor absent exactly when undeclared")
    void stepCoherence() {
        assertThatThrownBy(() -> new RoutingStep(
                        0, CARD, RoutingStepVerdict.CHOSEN,
                        Optional.of(RoutingRejection.UNAVAILABLE), true, Optional.of(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refused nothing");
        assertThatThrownBy(() -> new RoutingStep(
                        0, CARD, RoutingStepVerdict.REJECTED, Optional.empty(), true,
                        Optional.of(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names its reason");
        assertThatThrownBy(() -> new RoutingStep(
                        0, CARD, RoutingStepVerdict.ABANDONED,
                        Optional.of(RoutingRejection.UNAVAILABLE), true, Optional.of(1)))
                .as("an abandonment is knowledge and nothing else (INV-RAIL-02)")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("knowledge");
        assertThatThrownBy(() -> new RoutingStep(
                        0, CARD, RoutingStepVerdict.REJECTED,
                        Optional.of(RoutingRejection.UNDECLARED_BY_BUILD), true,
                        Optional.of(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("descriptor");
    }

    @Test
    @DisplayName("the decision's denormalised chosen rail is the trail's own, the trail is"
            + " contiguous, and steps exist only under a matched rule")
    void decisionCoherence() {
        assertThatThrownBy(() -> RoutingDecision.rehydrate(
                        RoutingDecisionId.next(IDS),
                        PaymentIntentId.next(IDS),
                        RoutingPolicyVersionId.next(IDS),
                        PaymentDirection.PAY_IN,
                        InstrumentKind.CARD_TOKEN,
                        AMOUNT,
                        Optional.of(0),
                        Optional.empty(),
                        List.of(chosen(0)),
                        Instant.now(CLOCK)))
                .as("a corrupt row: the trail chose, the column says nothing did")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("denormalised");
        assertThatThrownBy(() -> RoutingDecision.rehydrate(
                        RoutingDecisionId.next(IDS),
                        PaymentIntentId.next(IDS),
                        RoutingPolicyVersionId.next(IDS),
                        PaymentDirection.PAY_IN,
                        InstrumentKind.CARD_TOKEN,
                        AMOUNT,
                        Optional.of(0),
                        Optional.of(CARD),
                        List.of(chosen(1)),
                        Instant.now(CLOCK)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contiguous");
        assertThatThrownBy(() -> RoutingDecision.rehydrate(
                        RoutingDecisionId.next(IDS),
                        PaymentIntentId.next(IDS),
                        RoutingPolicyVersionId.next(IDS),
                        PaymentDirection.PAY_IN,
                        InstrumentKind.CARD_TOKEN,
                        AMOUNT,
                        Optional.empty(),
                        Optional.of(CARD),
                        List.of(chosen(0)),
                        Instant.now(CLOCK)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("matched rule");
    }

    @Test
    @DisplayName("the abandon door: once, only after a choice, and the second abandonment is"
            + " refused as the claim of a send that never happened")
    void theAbandonDoor() {
        RoutingDecision refused = RoutingDecision.rehydrate(
                RoutingDecisionId.next(IDS),
                PaymentIntentId.next(IDS),
                RoutingPolicyVersionId.next(IDS),
                PaymentDirection.PAY_IN,
                InstrumentKind.CARD_TOKEN,
                AMOUNT,
                Optional.of(0),
                Optional.empty(),
                List.of(new RoutingStep(
                        0, CARD, RoutingStepVerdict.REJECTED,
                        Optional.of(RoutingRejection.UNAVAILABLE), false, Optional.of(1))),
                Instant.now(CLOCK));
        assertThatThrownBy(refused::abandonedOnNothingSent)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nothing can be abandoned");

        RoutingDecision dispatched = RoutingDecision.rehydrate(
                RoutingDecisionId.next(IDS),
                PaymentIntentId.next(IDS),
                RoutingPolicyVersionId.next(IDS),
                PaymentDirection.PAY_IN,
                InstrumentKind.CARD_TOKEN,
                AMOUNT,
                Optional.of(0),
                Optional.of(CARD),
                List.of(chosen(0)),
                Instant.now(CLOCK));
        RoutingDecision abandoned = dispatched.abandonedOnNothingSent();
        assertThat(abandoned.steps()).hasSize(2);
        assertThat(abandoned.steps().get(1).verdict()).isEqualTo(RoutingStepVerdict.ABANDONED);
        assertThat(abandoned.steps().get(1).rejection())
                .contains(RoutingRejection.NOTHING_SENT);
        assertThat(abandoned.chosenRail())
                .as("the column keeps the rail that WAS dispatched - the historical fact")
                .contains(CARD);
        assertThatThrownBy(abandoned::abandonedOnNothingSent)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already abandoned");
    }

    private static RoutingStep chosen(int index) {
        return new RoutingStep(
                index, CARD, RoutingStepVerdict.CHOSEN, Optional.empty(), true,
                Optional.of(SimulatedCardPspAdapter.RAIL.declarationVersion()));
    }
}
