package com.finapp.crossborder;

/**
 * A Phase 13 seam's judgement of one cross-border instruction (`P9-TSK-016`, ADR-0081 point 8): permit
 * it, or refuse it.
 *
 * <p><strong>Two values and no reason, deliberately</strong> - {@code transfers.SeamVerdict}'s rule: the
 * mapping from a refusal to its error is the authorization's, fixed per seam ({@link CrossBorderLimitCheck}
 * answers {@code 422 crossborder.LimitRefused}, {@link CrossBorderRiskDecision}
 * {@code 422 crossborder.RiskRefused}), so an implementation can never borrow the other's vocabulary or
 * invent a third. A richer result here would be Phase 13 behaviour arriving early.
 */
public enum CrossBorderVerdict {

    /** The instruction may proceed - Phase 9's only produced value ({@link PermitAllUntilPhase13}). */
    PERMIT,

    /** The instruction is refused, with the seam's reserved code, and nothing is consumed. */
    REFUSE
}
