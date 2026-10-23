package com.finapp.risk;

/**
 * One window's figures as the caller sealed them (`P13-TSK-003`; {@code PHASE_13_PLAN.md} section 12.4;
 * {@code INV-RSK-03}): the count and the sum, in minor units of the policy's currency, <strong>before</strong> the
 * judged movement - what `-006`'s {@code VelocityWindows.read} answers T-eval, which upserts the increment only after
 * the verdict permits the movement (OQ-1). The engine never adds the judged amount into a figure: a sealed figure is
 * the window's figure verbatim, which is what {@code VelocityProof} recomputes.
 */
public record VelocityReading(long count, long sumMinor) {

    public VelocityReading {
        if (count < 0 || count > EvaluationInputs.MAX_COUNT) {
            throw new IllegalArgumentException("a window's count is 0 to " + EvaluationInputs.MAX_COUNT);
        }
        if (sumMinor < 0 || sumMinor > EvaluationInputs.MAX_MINOR) {
            throw new IllegalArgumentException("a window's sum is 0 to " + EvaluationInputs.MAX_MINOR + " minor units");
        }
    }
}
