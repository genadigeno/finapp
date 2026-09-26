package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.payments.RailCapabilities.DisputeModel;
import com.finapp.payments.RailCapabilities.Finality;
import com.finapp.payments.RailCapabilities.RefundMode;
import com.finapp.payments.RailCapabilities.Reversal;
import com.finapp.payments.RailCapabilities.SettlementModel;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The descriptor's coherence (`P7-TSK-001`, ADR-0059 §1): the combinations the decision
 * table makes structurally impossible are refused at construction, so an incoherent
 * declaration fails the build's own tests rather than mis-posting money.
 */
@DisplayName("RailCapabilities (P7-TSK-001)")
class RailCapabilitiesTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @Test
    @DisplayName("the three decided shapes construct: the card's, a push rail's, the book's")
    void theDecidedShapesConstruct() {
        assertThatCode(SimulatedCardPspAdapter.RAIL::capabilities).doesNotThrowAnyException();
        assertThatCode(RailCapabilitiesTest::push).doesNotThrowAnyException();
        assertThatCode(RailCapabilitiesTest::book).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a reversal, a refund mode or a finality foreign to the interaction model is"
            + " refused")
    void modelForeignDeclarationsAreRefused() {
        // A void on a push rail: there is no uncaptured authorization to release.
        assertThatThrownBy(() ->
                        new RailCapabilities(
                                InteractionModel.PUSH, Finality.FINAL_ON_ACCEPTANCE,
                                Set.of(Reversal.VOID), RefundMode.RETURN_PAYMENT,
                                SettlementModel.SCHEME_REPORTED,
                                Optional.of(Duration.ofSeconds(20)), DisputeModel.NONE,
                                Optional.empty(), Map.of(),
                                Optional.of(AccountPurpose.SETTLEMENT_CLEARING)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("void");
        // A provider refund on a push rail: there is no capture to refund against.
        assertThatThrownBy(() ->
                        new RailCapabilities(
                                InteractionModel.PUSH, Finality.FINAL_ON_ACCEPTANCE,
                                Set.of(), RefundMode.PROVIDER_REFUND,
                                SettlementModel.SCHEME_REPORTED,
                                Optional.of(Duration.ofSeconds(20)), DisputeModel.NONE,
                                Optional.empty(), Map.of(),
                                Optional.of(AccountPurpose.SETTLEMENT_CLEARING)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider refund");
        // A return payment on the card rail, and a book refund anywhere but the book.
        assertThatThrownBy(() -> card(c -> c.refundMode = RefundMode.RETURN_PAYMENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("return payment");
        assertThatThrownBy(() -> card(c -> c.refundMode = RefundMode.BOOK_REFUND))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("book refund");
        // Final-on-posting belongs to the book rail alone, in both directions.
        assertThatThrownBy(() -> card(c -> {
                    c.finality = Finality.FINAL_ON_POSTING;
                    c.disputes = DisputeModel.NONE;
                }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("final-on-posting");
    }

    @Test
    @DisplayName("settlement, the clearing position and the outcome deadline are refused out"
            + " of their models")
    void settlementAndDeadlineCoherence() {
        // The card rail cannot declare nothing-to-settle, nor lose its clearing position.
        assertThatThrownBy(() -> card(c -> c.settlement = SettlementModel.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("settle");
        assertThatThrownBy(() -> card(c -> c.clearingPurpose = Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clearing position");
        // The card's ambiguity ends only when the provider or a query says so.
        assertThatThrownBy(() -> card(c -> c.outcomeDeadline =
                        Optional.of(Duration.ofSeconds(20))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcome deadline");
        // A push rail must declare its scheme's bound, and a positive one.
        assertThatThrownBy(() ->
                        new RailCapabilities(
                                InteractionModel.PUSH, Finality.FINAL_ON_ACCEPTANCE,
                                Set.of(), RefundMode.RETURN_PAYMENT,
                                SettlementModel.SCHEME_REPORTED, Optional.empty(),
                                DisputeModel.NONE, Optional.empty(), Map.of(),
                                Optional.of(AccountPurpose.SETTLEMENT_CLEARING)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcome deadline");
        assertThatThrownBy(() ->
                        new RailCapabilities(
                                InteractionModel.PUSH, Finality.FINAL_ON_ACCEPTANCE,
                                Set.of(), RefundMode.RETURN_PAYMENT,
                                SettlementModel.SCHEME_REPORTED,
                                Optional.of(Duration.ZERO), DisputeModel.NONE,
                                Optional.empty(), Map.of(),
                                Optional.of(AccountPurpose.SETTLEMENT_CLEARING)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("chargebacks and the revocable window are one declaration, in both directions")
    void disputesAndFinalityAreOneDeclaration() {
        assertThatThrownBy(() -> card(c -> c.disputes = DisputeModel.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dispute window");
        assertThatThrownBy(() ->
                        new RailCapabilities(
                                InteractionModel.PUSH, Finality.FINAL_ON_ACCEPTANCE,
                                Set.of(), RefundMode.RETURN_PAYMENT,
                                SettlementModel.SCHEME_REPORTED,
                                Optional.of(Duration.ofSeconds(20)),
                                DisputeModel.CARD_SCHEME_CHARGEBACKS,
                                Optional.empty(), Map.of(),
                                Optional.of(AccountPurpose.SETTLEMENT_CLEARING)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dispute window");
    }

    @Test
    @DisplayName("currency restrictions and ceilings are coherent: no empty restriction, and"
            + " each ceiling positive, self-keyed, and carried")
    void currencyDeclarationsAreCoherent() {
        assertThatThrownBy(() -> card(c -> c.currencies = Optional.of(Set.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> card(c -> c.perCurrencyMaximum =
                        Map.of(EUR, Money.zero(EUR))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> card(c -> c.perCurrencyMaximum =
                        Map.of(CurrencyCode.of("GBP"), Money.ofMinorUnits(100, EUR))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("its own currency");
        assertThatThrownBy(() -> card(c -> {
                    c.currencies = Optional.of(Set.of(CurrencyCode.of("GBP")));
                    c.perCurrencyMaximum = Map.of(EUR, Money.ofMinorUnits(100, EUR));
                }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carries");
        assertThatCode(() -> card(c -> {
                    c.currencies = Optional.of(Set.of(EUR));
                    c.perCurrencyMaximum = Map.of(EUR, Money.ofMinorUnits(100, EUR));
                }))
                .doesNotThrowAnyException();
    }

    // ----------------------------------------------------------------- fixtures

    /** The decided push shape (ADR-0059 §1's middle column). */
    private static RailCapabilities push() {
        return new RailCapabilities(
                InteractionModel.PUSH, Finality.FINAL_ON_ACCEPTANCE, Set.of(),
                RefundMode.RETURN_PAYMENT, SettlementModel.SCHEME_REPORTED,
                Optional.of(Duration.ofSeconds(20)), DisputeModel.NONE, Optional.empty(),
                Map.of(), Optional.of(AccountPurpose.SETTLEMENT_CLEARING));
    }

    /** The decided book shape (the right column). */
    private static RailCapabilities book() {
        return new RailCapabilities(
                InteractionModel.BOOK, Finality.FINAL_ON_POSTING, Set.of(),
                RefundMode.BOOK_REFUND, SettlementModel.NONE, Optional.empty(),
                DisputeModel.NONE, Optional.empty(), Map.of(), Optional.empty());
    }

    /** The card shape with one declaration bent — the mutant each refusal case names. */
    private static RailCapabilities card(java.util.function.Consumer<Mutant> bend) {
        Mutant mutant = new Mutant();
        bend.accept(mutant);
        return new RailCapabilities(
                InteractionModel.TWO_STEP, mutant.finality, Set.of(Reversal.VOID),
                mutant.refundMode, mutant.settlement, mutant.outcomeDeadline, mutant.disputes,
                mutant.currencies, mutant.perCurrencyMaximum, mutant.clearingPurpose);
    }

    private static final class Mutant {
        Finality finality = Finality.REVOCABLE_UNTIL_DISPUTE_WINDOW_ENDS;
        RefundMode refundMode = RefundMode.PROVIDER_REFUND;
        SettlementModel settlement = SettlementModel.DEFERRED_VIA_CLEARING;
        Optional<Duration> outcomeDeadline = Optional.empty();
        DisputeModel disputes = DisputeModel.CARD_SCHEME_CHARGEBACKS;
        Optional<Set<CurrencyCode>> currencies = Optional.empty();
        Map<CurrencyCode, Money> perCurrencyMaximum = Map.of();
        Optional<AccountPurpose> clearingPurpose =
                Optional.of(AccountPurpose.SETTLEMENT_CLEARING);
    }
}
