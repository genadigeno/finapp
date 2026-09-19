package com.finapp.transfers;

/**
 * The risk-decision seam (`ROADMAP.md` §Refinement 2: introduced Phase 4, implemented
 * Phase 13). A <strong>required parameter</strong> of the execution command with no defaulted
 * overload, consulted <strong>in-lock</strong> on the execution's own unit of work —
 * {@link TransferLimitCheck}'s contract and reasoning, verbatim, for the sibling control; a
 * refusal commits {@code FAILED(RISK_REFUSED)}. The value-threshold step-up policy the
 * delivery plan once placed on transfers lives behind <em>this</em> seam when Phase 13 defines
 * it as a versioned policy artefact (`PHASE_4_PLAN.md` §11's recorded correction).
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface TransferRiskDecision<T> {

    /** Judges {@code transfer} under the held source lock, on the execution's own unit of work. */
    SeamVerdict check(T unitOfWork, Transfer transfer);
}
