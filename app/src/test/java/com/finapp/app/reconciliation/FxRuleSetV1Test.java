package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.reconciliation.Cardinality;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.ExternalLineType;
import com.finapp.reconciliation.RuleSetProposal;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The FX source's rule set v1 is PHASE_9_PLAN.md section 12.9.2 and O7, member for member (`P9-TSK-011`). */
@DisplayName("the FX source's rule set v1 is the plan's (P9-TSK-011)")
class FxRuleSetV1Test {

    private final RuleSetProposal v1 = FxRuleSetV1.proposal("FX source v1");

    @Test
    @DisplayName("the proposal is valid as the domain judges it - FX_FEE a priced fee line")
    void theProposalIsValid() {
        v1.validate();
    }

    @Test
    @DisplayName("the legs are ONE_TO_ONE by COVER_REF, grace 24 h, lag 2 days; the fee a CHECK by ORIGINAL_REF")
    void theRules() {
        assertThat(v1.sourceId()).isEqualTo(FxRuleSetV1.SOURCE);
        assertThat(v1.lagDays()).containsOnly(
                java.util.Map.entry(ExpectationKind.FX_SELL_LEG, 2), java.util.Map.entry(ExpectationKind.FX_BUY_LEG, 2));
        assertThat(v1.rules()).containsExactly(
                new RuleSetProposal.Rule(1, ExternalLineType.FX_SOLD, Optional.of("COVER_REF"),
                        Optional.of(ExpectationKind.FX_SELL_LEG), Cardinality.ONE_TO_ONE, false, 24),
                new RuleSetProposal.Rule(2, ExternalLineType.FX_BOUGHT, Optional.of("COVER_REF"),
                        Optional.of(ExpectationKind.FX_BUY_LEG), Cardinality.ONE_TO_ONE, false, 24),
                new RuleSetProposal.Rule(3, ExternalLineType.FX_FEE, Optional.of("ORIGINAL_REF"),
                        Optional.empty(), Cardinality.CHECK, false, 24));
    }

    @Test
    @DisplayName("the date window is 2 days and there is no fee tolerance; the FX fee is priced 0 + 0 in all five currencies")
    void theTermsAndTheSchedule() {
        assertThat(v1.tolerances()).singleElement().satisfies(tolerance -> {
            assertThat(tolerance.comparison()).isEqualTo("SETTLEMENT_DATE_DAYS");
            assertThat(tolerance.days()).contains(2);
        });
        assertThat(v1.feeSchedules()).hasSize(5).allSatisfy(terms -> {
            assertThat(terms.lineType()).isEqualTo(ExternalLineType.FX_FEE);
            assertThat(terms.rate()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(terms.fixedMinor()).isZero();
            assertThat(terms.scale()).isEqualTo(terms.currency().minorUnits());
        });
        assertThat(v1.feeSchedules()).extracting(RuleSetProposal.FeeTerms::currency)
                .extracting(CurrencyCode::code).containsExactlyInAnyOrder("EUR", "GBP", "USD", "JPY", "BHD");
    }

    @Test
    @DisplayName("the high-value thresholds are O6's worth in the five currencies")
    void theThresholds() {
        assertThat(v1.severityThresholds()).containsOnly(
                java.util.Map.entry(CurrencyCode.of("EUR"), 100_000L),
                java.util.Map.entry(CurrencyCode.of("GBP"), 100_000L),
                java.util.Map.entry(CurrencyCode.of("USD"), 100_000L),
                java.util.Map.entry(CurrencyCode.of("JPY"), 150_000L),
                java.util.Map.entry(CurrencyCode.of("BHD"), 400_000L));
    }
}
