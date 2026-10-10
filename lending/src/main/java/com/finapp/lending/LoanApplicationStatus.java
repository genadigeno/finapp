package com.finapp.lending;

/**
 * A loan application's lifecycle (`P11-TSK-002`; {@code LENDING_LIFECYCLES.md} section 4.3, built by `P11-TSK-010`
 * and `-011`).
 *
 * <p>{@code SUBMITTED -> AWAITING_DECISION} in the submitting transaction (credit's decision request opened beside
 * it); then {@code OFFERED} on an approved decision, {@code DECLINED} on a declined one, {@code CLOSED_UNDECIDED} when
 * credit's request closes without a decision, or {@code WITHDRAWN} by the customer while credit's request is still
 * cancellable. Terminal: every state but the first two. A decline is never reconsidered - a new application.
 */
public enum LoanApplicationStatus {

    /** Transient: kept so the history records the submission. */
    SUBMITTED,

    /** Credit's decision request is open; the application waits for its decision. */
    AWAITING_DECISION,

    /** Terminal: the decision was approved and the offer was born beside this state. */
    OFFERED,

    /** Terminal: the decision was declined. */
    DECLINED,

    /** Terminal: credit's request closed (cancelled, expired or abandoned) without a decision. */
    CLOSED_UNDECIDED,

    /** Terminal: the customer withdrew before credit evaluated. */
    WITHDRAWN
}
