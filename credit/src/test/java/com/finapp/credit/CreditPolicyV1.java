package com.finapp.credit;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Each product's policy v1 as {@code credit V008} seeds it - the suites' expectation of the seed, written apart. */
final class CreditPolicyV1 {

    /** PERSONAL_LOAN v1's fixed identity. */
    static final CreditPolicyVersionId PERSONAL_LOAN_ID =
            CreditPolicyVersionId.of(UUID.fromString("0190a1b2-5c0e-7000-8000-00000000d001"));

    /** CREDIT_LINE v1's fixed identity. */
    static final CreditPolicyVersionId CREDIT_LINE_ID =
            CreditPolicyVersionId.of(UUID.fromString("0190a1b2-5c0e-7000-8000-00000000d002"));

    static final CurrencyCode EUR = CurrencyCode.of("EUR");

    private CreditPolicyV1() {}

    static CreditPolicyVersionId id(CreditProduct product) {
        return product == CreditProduct.PERSONAL_LOAN ? PERSONAL_LOAN_ID : CREDIT_LINE_ID;
    }

    static CreditPolicy policy(CreditProduct product) {
        return policy(product, rules(product));
    }

    /** v1's parameters over {@code rules}. */
    static CreditPolicy policy(CreditProduct product, List<CreditPolicy.PolicyRule> rules) {
        boolean loan = product == CreditProduct.PERSONAL_LOAN;
        return new CreditPolicy(product, 900, eur(100_00), loan ? 300 : 500, eur(loan ? 40_000_00 : 20_000_00),
                Map.of(CreditSourceKind.BUREAU, Duration.ofDays(30), CreditSourceKind.FINANCIAL_DATA, Duration.ofDays(30)),
                UnavailableFallback.REFER, eur(loan ? 10_000_00 : 2_500_00), rules);
    }

    /** v1's eleven rules, in order - a mutable copy, so a case may take one away. */
    static List<CreditPolicy.PolicyRule> rules(CreditProduct product) {
        Money cap = eur(product == CreditProduct.PERSONAL_LOAN ? 5_000_00 : 1_000_00);
        return new ArrayList<>(List.of(
                rule("SOURCE_UNAVAILABLE_FALLBACK", attribute(CreditAttributeCode.SOURCE_UNAVAILABLE),
                        PolicyOperator.IS_PRESENT, new CreditPolicy.Operand.None(), PolicyEffect.REFER,
                        ReasonCode.SOURCE_UNAVAILABLE),
                rule("INSOLVENCY", attribute(CreditAttributeCode.BUREAU_INSOLVENCY_FLAG), PolicyOperator.EQ,
                        new CreditPolicy.Operand.BooleanOperand(true), PolicyEffect.HARD_DECLINE, ReasonCode.INSOLVENCY),
                rule("PRIOR_DEFAULT", attribute(CreditAttributeCode.BUREAU_DEFAULTS_72M), PolicyOperator.GE,
                        new CreditPolicy.Operand.IntegerOperand(1), PolicyEffect.DECLINE, ReasonCode.PRIOR_DEFAULT),
                rule("RECENT_DELINQUENCY", attribute(CreditAttributeCode.BUREAU_DELINQUENCIES_24M), PolicyOperator.GE,
                        new CreditPolicy.Operand.IntegerOperand(3), PolicyEffect.DECLINE, ReasonCode.RECENT_DELINQUENCY),
                rule("SCORE_FLOOR", figure(PolicyFigure.SCORE), PolicyOperator.LT,
                        new CreditPolicy.Operand.IntegerOperand(450), PolicyEffect.DECLINE, ReasonCode.SCORE_INSUFFICIENT),
                rule("SCORE_REFERRAL", figure(PolicyFigure.SCORE), PolicyOperator.LT,
                        new CreditPolicy.Operand.IntegerOperand(520), PolicyEffect.REFER, ReasonCode.RISK_REFERRAL),
                rule("AFFORDABILITY", figure(PolicyFigure.AFFORDABLE), PolicyOperator.EQ,
                        new CreditPolicy.Operand.BooleanOperand(false), PolicyEffect.DECLINE,
                        ReasonCode.AFFORDABILITY_INSUFFICIENT),
                rule("EXPOSURE_LIMIT", figure(PolicyFigure.EXPOSURE_HEADROOM), PolicyOperator.LT,
                        new CreditPolicy.Operand.MoneyOperand(eur(0)), PolicyEffect.DECLINE, ReasonCode.EXPOSURE_LIMIT),
                rule("INCOME_UNVERIFIED", attribute(CreditAttributeCode.FINDATA_MONTHLY_INCOME), PolicyOperator.IS_ABSENT,
                        new CreditPolicy.Operand.None(), PolicyEffect.REFER, ReasonCode.INCOME_UNVERIFIED),
                rule("FOREIGN_CURRENCY", attribute(CreditAttributeCode.CURRENCY_NOT_SUPPORTED), PolicyOperator.IS_PRESENT,
                        new CreditPolicy.Operand.None(), PolicyEffect.REFER, ReasonCode.CURRENCY_NOT_SUPPORTED),
                new CreditPolicy.PolicyRule("LOW_SCORE_CAP", figure(PolicyFigure.SCORE), PolicyOperator.LT,
                        new CreditPolicy.Operand.IntegerOperand(600), PolicyEffect.CAP_AMOUNT, Optional.of(cap),
                        ReasonCode.SCORE_INSUFFICIENT)));
    }

    static CreditPolicy.PolicyRule rule(
            String code, CreditPolicy.Subject subject, PolicyOperator operator, CreditPolicy.Operand operand,
            PolicyEffect effect, ReasonCode reason) {
        return new CreditPolicy.PolicyRule(code, subject, operator, operand, effect, Optional.empty(), reason);
    }

    static CreditPolicy.Subject attribute(CreditAttributeCode code) {
        return new CreditPolicy.Subject.Attribute(code);
    }

    static CreditPolicy.Subject figure(PolicyFigure figure) {
        return new CreditPolicy.Subject.Figure(figure);
    }

    static Money eur(long minorUnits) {
        return Money.ofMinorUnits(minorUnits, EUR);
    }
}
