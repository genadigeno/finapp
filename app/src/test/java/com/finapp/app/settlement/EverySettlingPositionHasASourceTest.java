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
import com.finapp.settlement.SettlementSourceDescriptor;
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
                            BookRail.RAIL,
                            com.finapp.payments.SimulatedCorridorAdapter.RAIL));

    /** The rails whose positions are all shared - the planted counterparty cases judge FX counterparties alone. */
    private static final PaymentRails SHARED_POSITION_RAILS =
            PaymentRails.of(List.of(SimulatedCardPspAdapter.RAIL, SimulatedInstantSchemeAdapter.RAIL, BookRail.RAIL));

    @Test
    @DisplayName("the composed register covers every declared settling rail and the payout")
    void theComposedRegisterCovers() {
        SettlementSources sources = SettlementBeans.composedSettlementSources(DECLARED);
        for (var rail : DECLARED.declaredIds()) {
            DECLARED.capabilitiesOf(rail)
                    .clearingPurpose()
                    .ifPresent(
                            position ->
                                    assertThat(position.ownerKind() == com.finapp.ledger.OwnerKind.COUNTERPARTY
                                                    ? sources.dischargedBy(position, rail.value())
                                                    : sources.dischargedBy(position))
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

    @Test
    @DisplayName("the corridor rail composes with its position and its source: corridor-sim-a.settlement,"
            + " SIM_CORRIDOR_CSV under PAYOUT_PROVIDER_REPORT, on its own CORRIDOR_CLEARING in USD, JPY"
            + " and BHD - and startup is refused without the source (P9-TSK-014)")
    void theCorridorComposesWithItsSource() {
        SettlementSources sources = SettlementBeans.composedSettlementSources(DECLARED);
        com.finapp.settlement.SettlementSourceDescriptor corridor = sources.byCode("corridor-sim-a.settlement").orElseThrow();
        assertThat(corridor.kind()).isEqualTo(com.finapp.settlement.SourceKind.PAYOUT_PROVIDER_REPORT);
        assertThat(corridor.format()).isEqualTo(com.finapp.settlement.SettlementFormatId.SIM_CORRIDOR_CSV);
        assertThat(corridor.settledPosition()).isEqualTo(
                com.finapp.payments.SimulatedCorridorAdapter.RAIL.capabilities().clearingPurpose());
        assertThat(corridor.settledCounterparty()).contains("corridor-sim-a");
        assertThat(corridor.settledCurrencies()).containsExactlyInAnyOrder(
                com.finapp.sharedkernel.money.CurrencyCode.of("USD"),
                com.finapp.sharedkernel.money.CurrencyCode.of("JPY"),
                com.finapp.sharedkernel.money.CurrencyCode.of("BHD"));
        assertThat(sources.attribute("XBA-20261005")).contains(corridor);
        assertThat(CounterpartyClearings.declared())
                .extracting(com.finapp.ledger.CounterpartyClearing::code)
                .contains("corridor-sim-a", "fx-sim-a");
        // Startup refused without the source: the counterparty's position has none.
        assertThatThrownBy(() -> SettlementBeans.composedSettlementSources(
                        DECLARED, CounterpartyClearings.declared(), SettlementBeans.fxProviderSources()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("counterparty 'corridor-sim-a'")
                .hasMessageContaining("INV-SET-05");
        // ...and without the counterparty's declaration the rail's own position is uncovered.
        assertThatThrownBy(() -> SettlementBeans.composedSettlementSources(
                        DECLARED,
                        CounterpartyClearings.declared().stream()
                                // Every corridor's declaration withdrawn (both rails since P9-TSK-026).
                                .filter(clearing -> !clearing.code().startsWith("corridor-sim-"))
                                .toList(),
                        SettlementBeans.fxProviderSources()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rail 'corridor-sim-")
                .hasMessageContaining("INV-SET-05");
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
                SHARED_POSITION_RAILS,
                List.of(clearing("fx-sim-a"), clearing("fx-sim-b")),
                List.of(source("fx-sim-a.trade-report", "fx-sim-a", java.util.Set.of(EUR)),
                        source("fx-sim-b.trade-report", "fx-sim-b", java.util.Set.of(EUR))));
        assertThat(composed.dischargedBy(AccountPurpose.FX_PROVIDER_CLEARING, "fx-sim-b")).isPresent();
        assertThat(com.finapp.app.reconciliation.PositionProof.provenPurposes(composed))
                .as("a counterparty admitted with its source is proven by construction")
                .contains(AccountPurpose.FX_PROVIDER_CLEARING);

        assertThatThrownBy(() -> SettlementBeans.composedSettlementSources(
                        SHARED_POSITION_RAILS, List.of(clearing("fx-sim-a")), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("counterparty 'fx-sim-a'")
                .hasMessageContaining("INV-SET-05");
        assertThatThrownBy(() -> SettlementBeans.composedSettlementSources(
                        SHARED_POSITION_RAILS, List.of(), List.of(source("fx-sim-a.trade-report", "fx-sim-a", java.util.Set.of(EUR)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("which declares no position");
        assertThatThrownBy(() -> SettlementBeans.composedSettlementSources(
                        SHARED_POSITION_RAILS,
                        List.of(clearing("fx-sim-a")),
                        List.of(source("fx-sim-a.trade-report", "fx-sim-a", java.util.Set.of(EUR)),
                                source("fx-sim-a.second-report", "fx-sim-a", java.util.Set.of(EUR)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INV-SET-05");
        assertThatThrownBy(() -> SettlementBeans.composedSettlementSources(
                        SHARED_POSITION_RAILS,
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
                        AccountPurpose.FX_PROVIDER_CLEARING,
                        com.finapp.payments.SimulatedCorridorAdapter.RAIL.capabilities().clearingPurpose().orElseThrow());
        assertThat(CounterpartyClearings.declared())
                .as("fx-sim-a, read off its FxProviderDeclaration (P9-TSK-011), and corridor-sim-a, read off"
                        + " its rail declaration (P9-TSK-014); their second siblings since P9-TSK-026")
                .extracting(com.finapp.ledger.CounterpartyClearing::code)
                .containsExactly("corridor-sim-a", "corridor-sim-b", "fx-sim-a", "fx-sim-b");
    }

    @Test
    @DisplayName("the second providers (P9-TSK-026): each counterparty its own source and its own remittance shape -"
            + " a bank line is attributed to exactly one; two sources sharing a shape refuse composition")
    void eachCounterpartyRemitsInItsOwnShape() {
        SettlementSources sources = SettlementBeans.composedSettlementSources(DECLARED);
        assertThat(sources.attribute("XBA-20261005")).map(SettlementSourceDescriptor::code).contains("corridor-sim-a.settlement");
        assertThat(sources.attribute("XBB-20261005")).map(SettlementSourceDescriptor::code).contains("corridor-sim-b.settlement");
        assertThat(sources.attribute("FXA-20261005")).map(SettlementSourceDescriptor::code).contains("fx-sim-a.trade-report");
        assertThat(sources.attribute("FXB-20261005")).map(SettlementSourceDescriptor::code).contains("fx-sim-b.trade-report");
        assertThat(sources.byCode("corridor-sim-b.settlement").orElseThrow().settledCounterparty()).contains("corridor-sim-b");
        assertThat(sources.byCode("fx-sim-b.trade-report").orElseThrow().settledCounterparty()).contains("fx-sim-b");

        List<SettlementSourceDescriptor> sharing = SettlementBeans.corridorSources().stream()
                .map(source -> new SettlementSourceDescriptor(source.code(), source.kind(), source.format(),
                        source.formatVersion(), source.channels(), source.settledPosition(),
                        Optional.of(com.finapp.settlement.format.simcorridor.SimCorridorCsvFormat.REMITTANCE_REFERENCE),
                        source.settledCounterparty(), source.settledCurrencies()))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        sharing.addAll(SettlementBeans.fxProviderSources());
        assertThatThrownBy(() -> SettlementBeans.composedSettlementSources(DECLARED, CounterpartyClearings.declared(), sharing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declare one remittance shape");
    }
}
