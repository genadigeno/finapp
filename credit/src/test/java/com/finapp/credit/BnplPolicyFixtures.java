package com.finapp.credit;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A credit policy per BNPL product for the suites (`P12-TSK-001`) - test data only: no BNPL policy is seeded or activated by
 * a migration, so a suite proposes one of these and two persons activate it.
 *
 * <p>Each is the personal loan's reviewed v1 ({@link CreditPolicyV1}) made automatic: every referring rule declines
 * instead, the fallback for an unavailable source is {@code DECLINE}, and the score cap is dropped (a purchase price is
 * fixed - an approval below it is no offer). The parameters: an assessment rate of 18% (pay-in-N's stress rate), EUR
 * 100.00 minimum disposable income, a maximum exposure of EUR 20,000.00, the product's maximum as the auto-approval
 * ceiling, and for pay-in-4 a stress factor of three instalments a month (30,000 basis points - three of its biweekly
 * instalments fall within any 30 days).
 */
final class BnplPolicyFixtures {

    /** Pay-in-N's stress rate, in basis points. */
    static final int STRESS_RATE_BPS = 1_800;

    /** Pay-in-4's stress factor, in basis points of one. */
    static final int PLAN_STRESS_FACTOR_BPS = 30_000;

    private BnplPolicyFixtures() {}

    /** {@code product}'s fixture. */
    static CreditPolicy policy(CreditProduct product) {
        return policy(product, rules());
    }

    /** {@code product}'s fixture over {@code rules}. */
    static CreditPolicy policy(CreditProduct product, List<CreditPolicy.PolicyRule> rules) {
        if (product.family() != CreditProduct.Family.BNPL) {
            throw new IllegalArgumentException(product + " is not a BNPL product");
        }
        return new CreditPolicy(product, STRESS_RATE_BPS, CreditPolicyV1.eur(100_00), 500, CreditPolicyV1.eur(20_000_00),
                Map.of(CreditSourceKind.BUREAU, Duration.ofDays(30), CreditSourceKind.FINANCIAL_DATA, Duration.ofDays(30)),
                UnavailableFallback.DECLINE, product.maximumAmount(), rules,
                product.termShape() == CreditProduct.TermShape.FIXED_PLAN ? Optional.of(PLAN_STRESS_FACTOR_BPS)
                        : Optional.empty());
    }

    /** The loan's v1 rules, every referral a decline, the cap dropped - a mutable copy. */
    static List<CreditPolicy.PolicyRule> rules() {
        List<CreditPolicy.PolicyRule> rules = new java.util.ArrayList<>();
        for (CreditPolicy.PolicyRule rule : CreditPolicyV1.rules(CreditProduct.PERSONAL_LOAN)) {
            if (rule.effect() == PolicyEffect.CAP_AMOUNT) {
                continue;
            }
            rules.add(rule.effect() != PolicyEffect.REFER ? rule : new CreditPolicy.PolicyRule(rule.ruleCode(),
                    rule.subject(), rule.operator(), rule.operand(), PolicyEffect.DECLINE, rule.cap(), rule.reason()));
        }
        return rules;
    }
}
