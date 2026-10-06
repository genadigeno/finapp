package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.payments.PaymentBeans;
import com.finapp.app.settlement.SettlementBeansAccess;
import com.finapp.payments.RailId;
import com.finapp.settlement.SettlementSources;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A settlement source's rail, per counterparty (`P9-TSK-026`): two corridor rails settle one purpose, and each
 * corridor's source resolves to its OWN rail - never the first rail of the purpose - so a provider reference on
 * {@code corridor-sim-b}'s report is looked up among {@code corridor-sim-b}'s executions alone ({@code INV-SET-05}).
 */
@DisplayName("a source's rail is its own counterparty's (P9-TSK-026)")
class RailOfSourceTest {

    @Test
    @DisplayName("corridor-sim-a's and corridor-sim-b's sources each resolve to their own rail; the instant scheme's"
            + " source, naming no counterparty, keeps its purpose's one rail; an FX provider's source names no rail")
    void eachSourceResolvesToItsOwnRail() {
        SettlementSources sources = SettlementBeansAccess.composed(PaymentBeans.DECLARED_RAILS);
        assertThat(ReconciliationBeans.railOfSource(sources.byCode("corridor-sim-a.settlement").orElseThrow(),
                PaymentBeans.DECLARED_RAILS)).contains(RailId.of("corridor-sim-a"));
        assertThat(ReconciliationBeans.railOfSource(sources.byCode("corridor-sim-b.settlement").orElseThrow(),
                PaymentBeans.DECLARED_RAILS)).contains(RailId.of("corridor-sim-b"));
        assertThat(ReconciliationBeans.railOfSource(sources.byCode("simulated-scheme.cycle-report").orElseThrow(),
                PaymentBeans.DECLARED_RAILS)).contains(RailId.of("instant"));
        assertThat(ReconciliationBeans.railOfSource(sources.byCode("fx-sim-b.trade-report").orElseThrow(),
                PaymentBeans.DECLARED_RAILS)).isEmpty();
    }
}
