package com.finapp.bnpl;

/**
 * A customer repayment obligation's lifecycle (`P12-TSK-002`; {@code BNPL_LIFECYCLES.md} section 4.7,
 * {@code PHASE_12_PLAN.md} section 5.5; built by `P12-TSK-013`, closed by the servicing, refund and payoff tasks).
 *
 * <p>Born {@code ACTIVE} at the acceptance; {@code CLOSED} - with its {@link BnplClosureReason} - by the transaction that
 * zeroes the last receivable. No {@code PENDING_DISBURSEMENT} (nothing to disburse) and no {@code CANCELLED} (once
 * financed, an obligation ends only by being settled or written off). Delinquency, default and a dispute's servicing hold are
 * conditions, never states. Terminal: {@code CLOSED} and {@code WRITTEN_OFF}, which closes nothing ({@code INV-LIFE-04}).
 */
public enum BnplObligationStatus {

    /** Serviced: billed, accrued (pay-in-N), collected and refundable. */
    ACTIVE,

    /** Terminal: every receivable zero, every elapsed date accrued, any credit balance refunded. */
    CLOSED,

    /**
     * Terminal (`P14-TSK-021`, owner decision C6): a defaulted obligation written off under four eyes - its five receivables moved to
     * {@code BNPL_WRITE_OFF_EXPENSE}. <strong>Never closed, never paid, never forgiven</strong>: an act on the platform's books that
     * says nothing about what the customer owes. No door moves money on it, nothing services it, and nothing leaves it
     * ({@code INV-LIFE-04}).
     */
    WRITTEN_OFF
}
