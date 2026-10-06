package com.finapp.app.reconciliation;

import com.finapp.reconciliation.Cardinality;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.ExternalLineType;
import com.finapp.reconciliation.RuleSetProposal;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The corridor source's rule set version 1 (`P9-TSK-014`; PHASE_9_PLAN.md section 12.9.2, owner
 * decision O7) - the exact proposal two controllers activate through
 * {@code POST /v1/operator/reconciliation/rule-sets} and its approval (OPERATIONS_RUNBOOK), never a
 * seed (D26). Held by {@code CorridorRuleSetV1Test}:
 *
 * <ul>
 *   <li>the payout {@code ONE_TO_ONE} to {@code CROSSBORDER_PAYOUT}, keyed {@code END_TO_END_REF} (our
 *       E) first, then {@code PAYOUT_PROVIDER_REF} (the provider's alias), grace 48 h;
 *   <li>the return operation-anchored to {@code CROSSBORDER_RETURN} - no key of its own, the line
 *       reaching the credit it returns by either reference - grace 72 h (the payout-return precedent);
 *   <li>a {@code PAYOUT_FEE} rule of cardinality {@code CHECK} (its original by {@code ORIGINAL_REF} =
 *       the provider's reference), priced 0 + USD 1.20 / JPY 180 / BHD 0.450;
 *   <li>lag 2 days for both kinds; {@code SETTLEMENT_DATE_DAYS} 2; no fee tolerance;
 *   <li>high-value thresholds for its three currencies at O6's worth (USD 1,000.00, JPY 150000, BHD 400.000).
 * </ul>
 */
public final class CorridorRuleSetV1 {

    /** {@code corridor-sim-a.settlement}'s seeded source row (settlement `V016`). */
    public static final UUID SOURCE = UUID.fromString("01a0e2bc-8200-7005-8000-000000000006");

    /** {@code corridor-sim-b.settlement}'s seeded source row (settlement `V017`, `P9-TSK-026`). */
    public static final UUID SOURCE_B = UUID.fromString("01a0e2bc-8200-7005-8000-000000000008");

    private CorridorRuleSetV1() {}

    /** The version-1 proposal for the corridor's source, with the proposer's reason. */
    public static RuleSetProposal proposal(String reason) {
        Map<CurrencyCode, Long> thresholds = new LinkedHashMap<>();
        thresholds.put(CurrencyCode.of("USD"), 100_000L);
        thresholds.put(CurrencyCode.of("JPY"), 150_000L);
        thresholds.put(CurrencyCode.of("BHD"), 400_000L);
        return proposal(SOURCE, thresholds, List.of(fee("USD", 120), fee("JPY", 180), fee("BHD", 450)), reason);
    }

    /** The same version 1 for {@code corridor-sim-b}'s source - USD, its only currency, at USD 1.20 (`P9-TSK-026`). */
    public static RuleSetProposal proposalB(String reason) {
        Map<CurrencyCode, Long> thresholds = new LinkedHashMap<>();
        thresholds.put(CurrencyCode.of("USD"), 100_000L);
        return proposal(SOURCE_B, thresholds, List.of(fee("USD", 120)), reason);
    }

    private static RuleSetProposal proposal(UUID source, Map<CurrencyCode, Long> thresholds,
            List<RuleSetProposal.FeeTerms> fees, String reason) {
        return new RuleSetProposal(
                source,
                2,
                90,
                Map.of(ExpectationKind.CROSSBORDER_PAYOUT, 2, ExpectationKind.CROSSBORDER_RETURN, 2),
                List.of(
                        new RuleSetProposal.Rule(1, ExternalLineType.PAYOUT_EXECUTED, Optional.of("END_TO_END_REF"),
                                Optional.of(ExpectationKind.CROSSBORDER_PAYOUT), Cardinality.ONE_TO_ONE, false, 48),
                        new RuleSetProposal.Rule(2, ExternalLineType.PAYOUT_EXECUTED, Optional.of("PAYOUT_PROVIDER_REF"),
                                Optional.of(ExpectationKind.CROSSBORDER_PAYOUT), Cardinality.ONE_TO_ONE, false, 48),
                        new RuleSetProposal.Rule(3, ExternalLineType.PAYOUT_RETURNED, Optional.of("END_TO_END_REF"),
                                Optional.of(ExpectationKind.CROSSBORDER_RETURN), Cardinality.ONE_TO_ONE, true, 72),
                        new RuleSetProposal.Rule(4, ExternalLineType.PAYOUT_RETURNED, Optional.of("PAYOUT_PROVIDER_REF"),
                                Optional.of(ExpectationKind.CROSSBORDER_RETURN), Cardinality.ONE_TO_ONE, true, 72),
                        new RuleSetProposal.Rule(5, ExternalLineType.PAYOUT_FEE, Optional.of("ORIGINAL_REF"),
                                Optional.empty(), Cardinality.CHECK, false, 48)),
                List.of(new RuleSetProposal.Tolerance("SETTLEMENT_DATE_DAYS", Optional.empty(), Optional.empty(),
                        Optional.of(2))),
                fees,
                thresholds,
                reason);
    }

    private static RuleSetProposal.FeeTerms fee(String code, long fixedMinor) {
        CurrencyCode currency = CurrencyCode.of(code);
        return new RuleSetProposal.FeeTerms(
                ExternalLineType.PAYOUT_FEE, currency, BigDecimal.ZERO.setScale(6), fixedMinor,
                currency.minorUnits(), "HALF_UP");
    }
}
