package com.finapp.bnpl;

/**
 * A customer repayment obligation's lifecycle (`P12-TSK-002`; {@code BNPL_LIFECYCLES.md} section 4.7,
 * {@code PHASE_12_PLAN.md} section 5.5; built by `P12-TSK-013`, closed by the servicing, refund and payoff tasks).
 *
 * <p>Born {@code ACTIVE} at the acceptance; {@code CLOSED} - with its {@link BnplClosureReason} - by the transaction that
 * zeroes the last receivable. No {@code PENDING_DISBURSEMENT} (nothing to disburse) and no {@code CANCELLED} (once
 * financed, an obligation ends only by being settled). Delinquency, default and a dispute's servicing hold are
 * conditions, never states. Terminal: {@code CLOSED} ({@code INV-LIFE-04}).
 */
public enum BnplObligationStatus {

    /** Serviced: billed, accrued (pay-in-N), collected and refundable. */
    ACTIVE,

    /** Terminal: every receivable zero, every elapsed date accrued, any credit balance refunded. */
    CLOSED
}
