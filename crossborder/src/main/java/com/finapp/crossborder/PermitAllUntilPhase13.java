package com.finapp.crossborder;

/**
 * The Phase 9 implementation of both cross-border seams (`P9-TSK-016`): permits everything, named for
 * exactly what it is - a reader meeting it in wiring sees that a control is deliberately absent and
 * which phase owes it ({@code transfers.PermitAllUntilPhase13}'s naming).
 *
 * <p><strong>Free of any logic and any state</strong>: no field exists, the unit of work is ignored, and
 * the seams' size is itself the assertion that no Phase 13 behaviour leaked early - held by
 * {@code CrossBorderSeamsTest}, not by review.
 *
 * @param <T> the transactional unit of work, ignored here
 */
public final class PermitAllUntilPhase13<T> implements CrossBorderLimitCheck<T>, CrossBorderRiskDecision<T> {

    @Override
    public CrossBorderVerdict check(T unitOfWork, CrossBorderInstruction instruction) {
        // Permit. The parameter being required is the control this phase ships.
        return CrossBorderVerdict.PERMIT;
    }
}
