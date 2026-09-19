package com.finapp.transfers;

/**
 * A seam's judgement of one transfer (`P4-TSK-010`): permit the movement, or refuse it.
 *
 * <p><strong>Two values and no reason, deliberately.</strong> The mapping from a refusal to its
 * committed {@link FailureReason} is the <em>execution's</em>, fixed per seam —
 * {@link TransferLimitCheck} refusals commit {@code FAILED(LIMIT_REFUSED)} and
 * {@link TransferRiskDecision} refusals {@code FAILED(RISK_REFUSED)} — so a limit
 * implementation can never commit the risk vocabulary or invent a third. A richer result shape
 * here would be Phase 13 behaviour arriving early, which `ROADMAP.md` §Refinement 2 forbids in
 * as many words: a seam is a documented interface, not a stub of the later domain.
 */
public enum SeamVerdict {

    /** The movement may proceed — Phase 4's only produced value ({@link PermitAllUntilPhase13}). */
    PERMIT,

    /**
     * The movement is refused: the execution commits the seam's reserved {@code FAILED} reason
     * as a domain outcome — replayable, audited, announced — never an exception leak
     * (ADR-0044's doctrine, and what makes Phase 13's arrival change no contract).
     */
    REFUSE
}
