package com.finapp.credit;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** {@code RETAIL_SCORECARD} v1 as {@code credit V006} seeds it - the suites' expectation of the seed, written apart. */
final class RetailScorecardV1 {

    /** The seed's fixed identity. */
    static final ScorecardModelVersionId ID = ScorecardModelVersionId.of(UUID.fromString("0190a1b2-5c0e-7000-8000-00000000c001"));

    private RetailScorecardV1() {}

    static Scorecard scorecard() {
        return new Scorecard(500, List.of(
                new Scorecard.AttributeBands(CreditAttributeCode.BUREAU_EXTERNAL_SCORE, -40, List.of(
                        new Scorecard.Range(null, 550L, -60),
                        new Scorecard.Range(550L, 650L, 0),
                        new Scorecard.Range(650L, 750L, 40),
                        new Scorecard.Range(750L, null, 80))),
                new Scorecard.AttributeBands(CreditAttributeCode.BUREAU_DELINQUENCIES_24M, -20, List.of(
                        new Scorecard.Range(null, 1L, 30),
                        new Scorecard.Range(1L, 3L, -30),
                        new Scorecard.Range(3L, null, -90))),
                new Scorecard.AttributeBands(CreditAttributeCode.BUREAU_DEFAULTS_72M, -20, List.of(
                        new Scorecard.Range(null, 1L, 20),
                        new Scorecard.Range(1L, null, -120))),
                new Scorecard.AttributeBands(CreditAttributeCode.BUREAU_ACTIVE_ACCOUNTS, 0, List.of(
                        new Scorecard.Range(null, 1L, -10),
                        new Scorecard.Range(1L, 6L, 10),
                        new Scorecard.Range(6L, null, -20))),
                new Scorecard.AttributeBands(CreditAttributeCode.BUREAU_INSOLVENCY_FLAG, -30, List.of(
                        new Scorecard.Codes(Set.of("false"), 10),
                        new Scorecard.Codes(Set.of("true"), -200)))));
    }
}
