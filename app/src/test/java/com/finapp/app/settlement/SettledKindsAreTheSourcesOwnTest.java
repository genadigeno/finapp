package com.finapp.app.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.reconciliation.ReconciliationBeans;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.settlement.SettlementSourceDescriptor;
import com.finapp.settlement.SourceKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The kinds each declared source's evidence settles, as the first-version door reads them (the Phase 9 to 10
 * transition, `SettledExpectationKinds`): every FX provider's source dates its cover legs, every corridor's source its
 * credits and their returns, and a source whose position no counterparty owns - the PSP, the instant scheme, the
 * merchant payout provider, the bank - declares none here (its version is migration-seeded with its lags).
 */
@DisplayName("each declared source settles its own kinds (the Phase 9 to 10 transition)")
class SettledKindsAreTheSourcesOwnTest {

    @Test
    @DisplayName("an FX provider's source settles its cover legs; a corridor's its credits and their returns")
    void theCounterpartySourcesSettleTheirOwnKinds() {
        assertThat(SettlementBeans.fxProviderSources()).isNotEmpty().allSatisfy(source ->
                assertThat(ReconciliationBeans.kindsSettledBy(source)).as(source.code())
                        .containsExactlyInAnyOrder(ExpectationKind.FX_SELL_LEG, ExpectationKind.FX_BUY_LEG));
        assertThat(SettlementBeans.corridorSources()).isNotEmpty().allSatisfy(source ->
                assertThat(ReconciliationBeans.kindsSettledBy(source)).as(source.code())
                        .containsExactlyInAnyOrder(ExpectationKind.CROSSBORDER_PAYOUT, ExpectationKind.CROSSBORDER_RETURN));
    }

    @Test
    @DisplayName("a source whose position no counterparty owns declares no kinds - even the payout provider's, whose kind"
            + " the corridors share")
    void theSharedPositionsDeclareNone() {
        java.util.List<SettlementSourceDescriptor> shared = SettlementBeans
                .composedSettlementSources(com.finapp.app.payments.PaymentBeans.DECLARED_RAILS).declared().stream()
                .filter(source -> source.settledCounterparty().isEmpty())
                .toList();
        assertThat(shared).extracting(SettlementSourceDescriptor::kind).contains(SourceKind.PAYOUT_PROVIDER_REPORT);
        assertThat(shared).allSatisfy(source ->
                assertThat(ReconciliationBeans.kindsSettledBy(source)).as(source.code()).isEmpty());
    }
}
