package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A rail's money semantics are frozen under its identifier (`P7-DOC-001`, ADR-0059 §1).
 *
 * <p><strong>Why a pin, and why per identifier.</strong> The platform stores a payment's
 * {@code rail} and each routing step's {@code descriptor_version}, but every resolver reads the
 * rail's capabilities from the RUNNING build: the clearing a capture or a withdrawal posts to,
 * the refund mode, whether a void is allowed. The Phase 7 review found that nothing stopped a
 * declaration from changing under a rail id with payments in flight and in history - a clearing
 * purpose edited in place would post the next outcome of an old payment somewhere its capture
 * never went. So the rule is written here, where a change must pass:
 *
 * <ul>
 *   <li><strong>The money semantics never change under a rail id</strong> - interaction model,
 *       finality, reversals, refund mode, settlement model, dispute model and clearing position.
 *       Changing any of them is a NEW rail id (a new declaration, a routing rule, a migration of
 *       nothing): old payments keep the semantics they were made under.
 *   <li><strong>Every other capability change bumps the declaration version</strong> - the
 *       number each routing step records - and this pin moves with it, in the same change.
 * </ul>
 */
@DisplayName("a rail's money semantics are frozen under its id (P7-DOC-001, ADR-0059)")
class RailMoneySemanticsArePinnedTest {

    /** The money semantics of each declared rail, by id: never to change under that id. */
    private record MoneySemantics(
            InteractionModel model,
            RailCapabilities.Finality finality,
            Set<RailCapabilities.Reversal> reversals,
            RailCapabilities.RefundMode refundMode,
            RailCapabilities.SettlementModel settlement,
            RailCapabilities.DisputeModel disputes,
            Optional<AccountPurpose> clearing,
            // Money semantics too (the Phase 7 -> 8 transition): the deadline decides when a
            // withdrawal's explicit "never seen" may conclude it and release its hold, read
            // from the running declaration - shortened under the same id, an old in-flight
            // withdrawal could be concluded while a re-send was still live.
            Optional<Duration> outcomeDeadline) {

        static MoneySemantics of(RailCapabilities declared) {
            return new MoneySemantics(
                    declared.interactionModel(),
                    declared.finality(),
                    declared.reversals(),
                    declared.refundMode(),
                    declared.settlement(),
                    declared.disputes(),
                    declared.clearingPurpose(),
                    declared.outcomeDeadline());
        }
    }

    private static final Map<String, MoneySemantics> FROZEN =
            Map.of(
                    "card",
                    new MoneySemantics(
                            InteractionModel.TWO_STEP,
                            RailCapabilities.Finality.REVOCABLE_UNTIL_DISPUTE_WINDOW_ENDS,
                            Set.of(RailCapabilities.Reversal.VOID),
                            RailCapabilities.RefundMode.PROVIDER_REFUND,
                            RailCapabilities.SettlementModel.DEFERRED_VIA_CLEARING,
                            RailCapabilities.DisputeModel.CARD_SCHEME_CHARGEBACKS,
                            Optional.of(AccountPurpose.SETTLEMENT_CLEARING),
                            Optional.empty()),
                    "instant",
                    new MoneySemantics(
                            InteractionModel.PUSH,
                            RailCapabilities.Finality.FINAL_ON_ACCEPTANCE,
                            Set.of(),
                            RailCapabilities.RefundMode.RETURN_PAYMENT,
                            RailCapabilities.SettlementModel.SCHEME_REPORTED,
                            RailCapabilities.DisputeModel.NONE,
                            Optional.of(AccountPurpose.INSTANT_CLEARING),
                            Optional.of(Duration.ofSeconds(90))),
                    "book",
                    new MoneySemantics(
                            InteractionModel.BOOK,
                            RailCapabilities.Finality.FINAL_ON_POSTING,
                            Set.of(),
                            RailCapabilities.RefundMode.BOOK_REFUND,
                            RailCapabilities.SettlementModel.NONE,
                            RailCapabilities.DisputeModel.NONE,
                            Optional.empty(),
                            Optional.empty()),
                    // P9-TSK-014 (ADR-0080 section 1, D17): credits only - final on acceptance, no
                    // reversal, NO refund (no pay-in), cleared on the provider's own position.
                    "corridor-sim-a",
                    new MoneySemantics(
                            InteractionModel.PUSH,
                            RailCapabilities.Finality.FINAL_ON_ACCEPTANCE,
                            Set.of(),
                            RailCapabilities.RefundMode.NONE,
                            RailCapabilities.SettlementModel.DEFERRED_VIA_CLEARING,
                            RailCapabilities.DisputeModel.NONE,
                            Optional.of(AccountPurpose.CORRIDOR_CLEARING),
                            Optional.of(Duration.ofMinutes(10))));

    private static final Set<PaymentRail> DECLARED =
            Set.of(
                    SimulatedCardPspAdapter.RAIL,
                    SimulatedInstantSchemeAdapter.RAIL,
                    BookRail.RAIL,
                    SimulatedCorridorAdapter.RAIL);

    private static final com.finapp.sharedkernel.money.CurrencyCode USD =
            com.finapp.sharedkernel.money.CurrencyCode.of("USD");
    private static final com.finapp.sharedkernel.money.CurrencyCode JPY =
            com.finapp.sharedkernel.money.CurrencyCode.of("JPY");
    private static final com.finapp.sharedkernel.money.CurrencyCode BHD =
            com.finapp.sharedkernel.money.CurrencyCode.of("BHD");

    /** The restricted rails' currencies and ceilings, per declaration version (`P9-TSK-014`). */
    private static final Map<String, Map<com.finapp.sharedkernel.money.CurrencyCode, com.finapp.sharedkernel.money.Money>>
            CEILINGS =
                    Map.of(
                            "corridor-sim-a",
                            Map.of(
                                    USD, com.finapp.sharedkernel.money.Money.of(new java.math.BigDecimal("10000.00"), USD),
                                    JPY, com.finapp.sharedkernel.money.Money.of(new java.math.BigDecimal("1500000"), JPY),
                                    BHD, com.finapp.sharedkernel.money.Money.of(new java.math.BigDecimal("4000.000"), BHD)));

    @Test
    @DisplayName("every declared rail's money semantics equal the ones frozen under its id - a"
            + " change to any of them is a new rail id, never an edit")
    void moneySemanticsAreFrozenPerRailId() {
        assertThat(DECLARED)
                .extracting(rail -> rail.id().value())
                .as("every declared rail is pinned, and nothing pinned is undeclared")
                .containsExactlyInAnyOrderElementsOf(FROZEN.keySet());
        for (PaymentRail rail : DECLARED) {
            assertThat(MoneySemantics.of(rail.capabilities()))
                    .as("rail '%s': its money semantics are frozen under its id - old payments"
                            + " resolve their clearing, refund mode and reversals from the"
                            + " running declaration, so a change here re-means history; declare"
                            + " a new rail id instead (ADR-0059 section 1)", rail.id().value())
                    .isEqualTo(FROZEN.get(rail.id().value()));
        }
    }

    @Test
    @DisplayName("the rest of each declaration is pinned to its declaration version - a change"
            + " bumps the version the routing steps record, and this pin, together")
    void everyOtherCapabilityIsPinnedToItsVersion() {
        Map<String, Integer> versions = Map.of("card", 1, "instant", 1, "book", 1, "corridor-sim-a", 1);
        for (PaymentRail rail : DECLARED) {
            RailCapabilities declared = rail.capabilities();
            String id = rail.id().value();
            assertThat(rail.declarationVersion())
                    .as("rail '%s': the declaration version this pin describes", id)
                    .isEqualTo(versions.get(id));
            Map<com.finapp.sharedkernel.money.CurrencyCode, com.finapp.sharedkernel.money.Money> ceilings =
                    CEILINGS.getOrDefault(id, Map.of());
            assertThat(declared.currencies())
                    .as("rail '%s' v%d: currencies", id, rail.declarationVersion())
                    .isEqualTo(ceilings.isEmpty() ? Optional.empty() : Optional.of(ceilings.keySet()));
            assertThat(declared.perCurrencyMaximum())
                    .as("rail '%s' v%d: per-currency maximum", id, rail.declarationVersion())
                    .isEqualTo(ceilings);
        }
    }
}
