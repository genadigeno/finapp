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
}
