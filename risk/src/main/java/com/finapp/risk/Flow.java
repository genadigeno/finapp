package com.finapp.risk;

/**
 * A subject's inflow and outflow sums in one window, in minor units (`P13-TSK-003`; {@link RuleKind#RAPID_IN_OUT}) -
 * the sweep's reading (`-020`). Each at most {@link EvaluationInputs#MAX_MINOR}, so the share in basis points is
 * compared by cross-multiplication in {@code long} without overflow and without a division ({@code INV-MON-01}).
 */
public record Flow(long inMinor, long outMinor) {

    public Flow {
        if (inMinor < 0 || inMinor > EvaluationInputs.MAX_MINOR || outMinor < 0 || outMinor > EvaluationInputs.MAX_MINOR) {
            throw new IllegalArgumentException("a flow's sums are 0 to " + EvaluationInputs.MAX_MINOR + " minor units");
        }
    }

    /** True when the inflow is positive and the outflow is at or above {@code ratioBps} of it. */
    public boolean outAtLeast(int ratioBps) {
        return inMinor > 0 && outMinor * 10_000L >= (long) ratioBps * inMinor;
    }
}
