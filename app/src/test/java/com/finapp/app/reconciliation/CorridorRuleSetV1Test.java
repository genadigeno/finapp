package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.reconciliation.Cardinality;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.ExternalLineType;
import com.finapp.reconciliation.RuleSetProposal;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The corridor source's rule set v1 is PHASE_9_PLAN.md section 12.9.2 and O7, member for member (`P9-TSK-014`). */
@DisplayName("the corridor source's rule set v1 is the plan's (P9-TSK-014)")
class CorridorRuleSetV1Test {

    private final RuleSetProposal v1 = CorridorRuleSetV1.proposal("corridor source v1");

    @Test
    @DisplayName("the proposal is valid as the domain judges it - PAYOUT_FEE a priced fee line")
    void theProposalIsValid() {
        v1.validate();
    }

    @Test
    @DisplayName("the payout ONE_TO_ONE by END_TO_END_REF then PAYOUT_PROVIDER_REF; the return operation-anchored,"
            + " grace 72 h; the fee a CHECK by ORIGINAL_REF; lag 2 days each")
    void theRules() {
        assertThat(v1.sourceId()).isEqualTo(CorridorRuleSetV1.SOURCE);
        assertThat(v1.lagDays()).containsOnly(
                Map.entry(ExpectationKind.CROSSBORDER_PAYOUT, 2), Map.entry(ExpectationKind.CROSSBORDER_RETURN, 2));
        assertThat(v1.rules()).containsExactly(
                new RuleSetProposal.Rule(1, ExternalLineType.PAYOUT_EXECUTED, Optional.of("END_TO_END_REF"),
                        Optional.of(ExpectationKind.CROSSBORDER_PAYOUT), Cardinality.ONE_TO_ONE, false, 48),
                new RuleSetProposal.Rule(2, ExternalLineType.PAYOUT_EXECUTED, Optional.of("PAYOUT_PROVIDER_REF"),
                        Optional.of(ExpectationKind.CROSSBORDER_PAYOUT), Cardinality.ONE_TO_ONE, false, 48),
                new RuleSetProposal.Rule(3, ExternalLineType.PAYOUT_RETURNED, Optional.of("END_TO_END_REF"),
                        Optional.of(ExpectationKind.CROSSBORDER_RETURN), Cardinality.ONE_TO_ONE, true, 72),
                new RuleSetProposal.Rule(4, ExternalLineType.PAYOUT_RETURNED, Optional.of("PAYOUT_PROVIDER_REF"),
                        Optional.of(ExpectationKind.CROSSBORDER_RETURN), Cardinality.ONE_TO_ONE, true, 72),
                new RuleSetProposal.Rule(5, ExternalLineType.PAYOUT_FEE, Optional.of("ORIGINAL_REF"),
                        Optional.empty(), Cardinality.CHECK, false, 48));
        assertThat(ExternalLineType.PAYOUT_FEE.originalKeyKind())
                .as("the fee's original is read by the provider's reference - the format writes exactly that")
                .contains(com.finapp.reconciliation.KeyKind.PAYOUT_PROVIDER_REF);
    }

    @Test
    @DisplayName("the date window is 2 days, no fee tolerance, and PAYOUT_FEE priced 0 + USD 1.20 / JPY 180 / BHD 0.450")
    void theTermsAndTheSchedule() {
        assertThat(v1.tolerances()).singleElement().satisfies(tolerance -> {
            assertThat(tolerance.comparison()).isEqualTo("SETTLEMENT_DATE_DAYS");
            assertThat(tolerance.days()).contains(2);
        });
        assertThat(v1.feeSchedules()).allSatisfy(terms -> {
            assertThat(terms.lineType()).isEqualTo(ExternalLineType.PAYOUT_FEE);
            assertThat(terms.rate()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(terms.scale()).isEqualTo(terms.currency().minorUnits());
        });
        assertThat(v1.feeSchedules())
                .extracting(terms -> terms.currency().code() + "=" + terms.fixedMinor())
                .containsExactly("USD=120", "JPY=180", "BHD=450");
    }

    @Test
    @DisplayName("the high-value thresholds are O6's worth in the corridor's three currencies")
    void theThresholds() {
        assertThat(v1.severityThresholds()).containsOnly(
                Map.entry(CurrencyCode.of("USD"), 100_000L),
                Map.entry(CurrencyCode.of("JPY"), 150_000L),
                Map.entry(CurrencyCode.of("BHD"), 400_000L));
    }
}
