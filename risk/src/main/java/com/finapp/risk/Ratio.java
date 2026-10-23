package com.finapp.risk;

/**
 * A share as the two figures it is made of - refunds over payments, chargebacks over payments (`P13-TSK-003`;
 * {@link RuleKind#REFUND_RATIO}, {@link RuleKind#CHARGEBACK_RATIO}; `-014` says what each counts). Sealed as the
 * figures and never as a quotient, so the comparison with a policy's basis points is exact: cross-multiplied in
 * {@code long} under the {@link EvaluationInputs#MAX_MINOR} bound, no division, no rounding ({@code INV-MON-01}).
 */
public record Ratio(long numerator, long denominator) {

    public Ratio {
        if (numerator < 0 || numerator > EvaluationInputs.MAX_MINOR
                || denominator < 0 || denominator > EvaluationInputs.MAX_MINOR) {
            throw new IllegalArgumentException("a ratio's figures are 0 to " + EvaluationInputs.MAX_MINOR);
        }
    }

    /** True when the denominator is positive and the share is at or above {@code ratioBps}. */
    public boolean atLeast(int ratioBps) {
        return denominator > 0 && numerator * 10_000L >= (long) ratioBps * denominator;
    }
}
