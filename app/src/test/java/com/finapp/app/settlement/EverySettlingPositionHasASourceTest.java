package com.finapp.app.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.merchant.PayoutSettlementDeclaration;
import com.finapp.payments.BookRail;
import com.finapp.payments.PaymentRail;
import com.finapp.payments.PaymentRails;
import com.finapp.payments.RailCapabilities;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.SimulatedInstantSchemeAdapter;
import com.finapp.settlement.SettlementSources;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code INV-SET-05}'s composition rank (`P8-TSK-002`, ADR-0064): every externally settling
 * position has exactly one declared source, verified where the register is composed — so an
 * uncovered settling rail fails this build and every startup, never a payment.
 */
@DisplayName("every settling position has a source (P8-TSK-002, INV-SET-05)")
class EverySettlingPositionHasASourceTest {

    private static final PaymentRails DECLARED =
            PaymentRails.of(
                    List.of(
                            SimulatedCardPspAdapter.RAIL,
                            SimulatedInstantSchemeAdapter.RAIL,
                            BookRail.RAIL));

    @Test
    @DisplayName("the composed register covers every declared settling rail and the payout")
    void theComposedRegisterCovers() {
        SettlementSources sources = SettlementBeans.composedSettlementSources(DECLARED);
        for (var rail : DECLARED.declaredIds()) {
            DECLARED.capabilitiesOf(rail)
                    .clearingPurpose()
                    .ifPresent(
                            position ->
                                    assertThat(sources.dischargedBy(position))
                                            .as("rail '%s' settles on %s", rail.value(), position)
                                            .isPresent());
        }
        assertThat(sources.dischargedBy(PayoutSettlementDeclaration.CLEARING_PURPOSE))
                .as("the payout's declared position is covered")
                .isPresent();
        assertThat(sources.byCode("simulated-bank.statement"))
                .as("the bank source is registered by identity; its cash position binds with"
                        + " its first poster (P8-TSK-016)")
                .isPresent()
                .get()
                .satisfies(bank -> assertThat(bank.settledPosition()).isEmpty());
    }

    @Test
    @DisplayName("the guard is not vacuous: a planted uncovered rail is refused, naming its"
            + " position")
    void aPlantedUncoveredRailIsRefused() {
        RailCapabilities instant = SimulatedInstantSchemeAdapter.RAIL.capabilities();
        // The instant declaration with a position no source discharges - a fourth rail a
        // later phase adds without touching the register.
        RailCapabilities uncovered =
                new RailCapabilities(
                        instant.interactionModel(),
                        instant.finality(),
                        instant.reversals(),
                        instant.refundMode(),
                        instant.settlement(),
                        instant.outcomeDeadline(),
                        instant.disputes(),
                        instant.currencies(),
                        instant.perCurrencyMaximum(),
                        Optional.of(AccountPurpose.CHARGEBACK_RECOVERABLE));
        PaymentRails planted =
                PaymentRails.of(
                        List.of(
                                SimulatedCardPspAdapter.RAIL,
                                SimulatedInstantSchemeAdapter.RAIL,
                                BookRail.RAIL,
                                new PaymentRail(
                                        new com.finapp.payments.RailId("planted"), 1, uncovered)));
        assertThatThrownBy(() -> SettlementBeans.composedSettlementSources(planted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("planted")
                .hasMessageContaining("INV-SET-05");
        // The control beside the probe: without the plant, composition succeeds.
        assertThatCode(() -> SettlementBeans.composedSettlementSources(DECLARED))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------- per counterparty (P9-TSK-010, ADR-0078 section 6)

    private static final com.finapp.sharedkernel.money.CurrencyCode EUR =
            com.finapp.sharedkernel.money.CurrencyCode.of("EUR");

    private static com.finapp.ledger.CounterpartyClearing clearing(String code) {
        return new com.finapp.ledger.CounterpartyClearing(
                code, com.finapp.ledger.CounterpartyKind.FX_PROVIDER, AccountPurpose.FX_PROVIDER_CLEARING,
                java.util.Set.of(EUR));
    }

    private static com.finapp.settlement.SettlementSourceDescriptor source(
            String code, String counterparty, java.util.Set<com.finapp.sharedkernel.money.CurrencyCode> currencies) {
        return new com.finapp.settlement.SettlementSourceDescriptor(
                code,
                com.finapp.settlement.SourceKind.PSP_SETTLEMENT_REPORT,
                com.finapp.settlement.SettlementFormatId.SIM_PSP_CSV,
                1,
                java.util.Set.of(com.finapp.settlement.DeliveryChannel.UPLOAD),
                Optional.of(AccountPurpose.FX_PROVIDER_CLEARING),
                Optional.of(code.replace('.', '-').toUpperCase() + "-[0-9]{4}"),
                Optional.of(counterparty),
                currencies);
    }

    @Test
    @DisplayName("each declared counterparty position has its own source - planted: a counterparty"
            + " with none, a source with no declared counterparty, a second source on one"
            + " counterparty, a currency disagreement - each refuses composition")
    void everyCounterpartyPositionHasItsSource() {
        SettlementSources composed = SettlementBeans.composedSettlementSources(
                DECLARED,
                List.of(clearing("fx-sim-a"), clearing("fx-sim-b")),
                List.of(source("fx-sim-a.trade-report", "fx-sim-a", java.util.Set.of(EUR)),
                        source("fx-sim-b.trade-report", "fx-sim-b", java.util.Set.of(EUR))));
        assertThat(composed.dischargedBy(AccountPurpose.FX_PROVIDER_CLEARING, "fx-sim-b")).isPresent();
        assertThat(com.finapp.app.reconciliation.PositionProof.provenPurposes(composed))
                .as("a counterparty admitted with its source is proven by construction")
                .contains(AccountPurpose.FX_PROVIDER_CLEARING);

        assertThatThrownBy(() -> SettlementBeans.composedSettlementSources(
                        DECLARED, List.of(clearing("fx-sim-a")), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("counterparty 'fx-sim-a'")
                .hasMessageContaining("INV-SET-05");
        assertThatThrownBy(() -> SettlementBeans.composedSettlementSources(
                        DECLARED, List.of(), List.of(source("fx-sim-a.trade-report", "fx-sim-a", java.util.Set.of(EUR)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("which declares no position");
        assertThatThrownBy(() -> SettlementBeans.composedSettlementSources(
                        DECLARED,
                        List.of(clearing("fx-sim-a")),
                        List.of(source("fx-sim-a.trade-report", "fx-sim-a", java.util.Set.of(EUR)),
                                source("fx-sim-a.second-report", "fx-sim-a", java.util.Set.of(EUR)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INV-SET-05");
        assertThatThrownBy(() -> SettlementBeans.composedSettlementSources(
                        DECLARED,
                        List.of(clearing("fx-sim-a")),
                        List.of(source("fx-sim-a.trade-report", "fx-sim-a",
                                java.util.Set.of(EUR, com.finapp.sharedkernel.money.CurrencyCode.of("USD"))))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("one declaration");
    }

    @Test
    @DisplayName("the proof's purposes are derived from the composed register - the three clearings the"
            + " hard-coded list named, and fx-sim-a's position since P9-TSK-011 (ADR-0078 section 8)")
    void theProvenPurposesAreDerived() {
        assertThat(com.finapp.app.reconciliation.PositionProof.provenPurposes(
                        SettlementBeans.composedSettlementSources(DECLARED)))
                .containsExactlyInAnyOrder(
                        AccountPurpose.SETTLEMENT_CLEARING,
                        AccountPurpose.INSTANT_CLEARING,
                        AccountPurpose.PAYOUT_CLEARING,
                        AccountPurpose.FX_PROVIDER_CLEARING);
        assertThat(CounterpartyClearings.declared())
                .as("fx-sim-a, read off its FxProviderDeclaration (P9-TSK-011)")
                .singleElement()
                .satisfies(clearing -> assertThat(clearing.code()).isEqualTo("fx-sim-a"));
    }
}
