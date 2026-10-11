package com.finapp.lending;

/**
 * How a borrower repays (`P11-TSK-010`; {@code PHASE_11_PLAN.md} section 9, A11, A21) - chosen on the application and
 * accepted with the agreement, the authority the auto-collection step acts on (`P11-TSK-019`).
 *
 * <p><strong>Only implemented members.</strong> {@code WALLET} alone: money reaches a loan only from the borrower's
 * wallet - repaid by the borrower, or collected automatically when the pinned terms say so (A21). An external inbound
 * repayment or a direct debit (A11) is a new member together with the rail that carries it.
 */
public enum RepaymentMethod {

    /** From the borrower's own wallet in the loan's currency. */
    WALLET
}
