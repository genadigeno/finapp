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
 * The FX provider source's rule set version 1 (`P9-TSK-011`; PHASE_9_PLAN.md section 12.9.2, owner
 * decision O7) - the exact proposal two controllers activate through
 * {@code POST /v1/operator/reconciliation/rule-sets} and its approval (OPERATIONS_RUNBOOK), never a
 * seed (D26). Held by {@code FxRuleSetV1Test}:
 *
 * <ul>
 *   <li>the legs {@code ONE_TO_ONE} keyed {@code COVER_REF}, grace 24 h, lag 2 days each;
 *   <li>an {@code FX_FEE} rule of cardinality {@code CHECK}, its original named by
 *       {@code ORIGINAL_REF} = the cover reference, priced 0 + 0 in all five currencies - the simulated
 *       provider earns its spread and bills nothing, so any reported FX fee is a {@code FEE_MISMATCH};
 *   <li>{@code SETTLEMENT_DATE_DAYS} 2, no fee tolerance (absent reads zero);
 *   <li>high-value thresholds for the five currencies at O6's worth (EUR/GBP/USD 1,000.00, JPY
 *       150000, BHD 400.000).
 * </ul>
 */
public final class FxRuleSetV1 {

    /** {@code fx-sim-a.trade-report}'s seeded source row (settlement `V015`). */
    public static final UUID SOURCE = UUID.fromString("01a0e2bc-8200-7005-8000-000000000005");

    private static final List<String> CURRENCIES = List.of("EUR", "GBP", "USD", "JPY", "BHD");

    private FxRuleSetV1() {}

    /** The version-1 proposal for the FX provider's source, with the proposer's reason. */
    public static RuleSetProposal proposal(String reason) {
        Map<CurrencyCode, Long> thresholds = new LinkedHashMap<>();
        thresholds.put(CurrencyCode.of("EUR"), 100_000L);
        thresholds.put(CurrencyCode.of("GBP"), 100_000L);
        thresholds.put(CurrencyCode.of("USD"), 100_000L);
        thresholds.put(CurrencyCode.of("JPY"), 150_000L);
        thresholds.put(CurrencyCode.of("BHD"), 400_000L);
        return new RuleSetProposal(
                SOURCE,
                2,
                90,
                Map.of(ExpectationKind.FX_SELL_LEG, 2, ExpectationKind.FX_BUY_LEG, 2),
                List.of(
                        new RuleSetProposal.Rule(1, ExternalLineType.FX_SOLD, Optional.of("COVER_REF"),
                                Optional.of(ExpectationKind.FX_SELL_LEG), Cardinality.ONE_TO_ONE, false, 24),
                        new RuleSetProposal.Rule(2, ExternalLineType.FX_BOUGHT, Optional.of("COVER_REF"),
                                Optional.of(ExpectationKind.FX_BUY_LEG), Cardinality.ONE_TO_ONE, false, 24),
                        new RuleSetProposal.Rule(3, ExternalLineType.FX_FEE, Optional.of("ORIGINAL_REF"),
                                Optional.empty(), Cardinality.CHECK, false, 24)),
                List.of(new RuleSetProposal.Tolerance("SETTLEMENT_DATE_DAYS", Optional.empty(), Optional.empty(),
                        Optional.of(2))),
                CURRENCIES.stream()
                        .map(CurrencyCode::of)
                        .map(currency -> new RuleSetProposal.FeeTerms(
                                ExternalLineType.FX_FEE, currency, BigDecimal.ZERO.setScale(6), 0,
                                currency.minorUnits(), "HALF_UP"))
                        .toList(),
                thresholds,
                reason);
    }
}
