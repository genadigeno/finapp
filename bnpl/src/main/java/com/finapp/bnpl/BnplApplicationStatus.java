package com.finapp.bnpl;

/**
 * A BNPL application's lifecycle (`P12-TSK-002`; {@code BNPL_LIFECYCLES.md} section 4.2, {@code PHASE_12_PLAN.md}
 * section 5.1; built by `P12-TSK-010` and offered by `-011`).
 *
 * <p>{@code SUBMITTED -> AWAITING_DECISION} in the submitting transaction (credit's decision request opened beside it,
 * one per purchase - B5); then {@code OFFERED} on an approved, offerable decision, {@code DECLINED} on a declined one,
 * {@code CLOSED_UNDECIDED} when no offer can be made (its {@link BnplApplicationClosureReason}), or {@code WITHDRAWN} by
 * the customer while credit's request is still cancellable. Terminal: every state but the first two. A decline is never
 * reconsidered - a new application.
 */
public enum BnplApplicationStatus {

    /** Transient: kept so the history records the submission. */
    SUBMITTED,

    /** Credit's decision request is open; the application waits for its decision. */
    AWAITING_DECISION,

    /** Terminal: the decision was approved and offerable, and the offer was born beside this state. */
    OFFERED,

    /** Terminal: the decision was declined. */
    DECLINED,

    /** Terminal: no offer can be made - credit's request closed undecided, the session closed, or the approval unofferable. */
    CLOSED_UNDECIDED,

    /** Terminal: the customer withdrew before credit evaluated. */
    WITHDRAWN
}
