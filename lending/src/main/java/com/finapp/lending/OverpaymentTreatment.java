package com.finapp.lending;

/**
 * What happens to the part of a repayment that exceeds what is due (`P11-TSK-002`; L6, A26, ADR-0095) - a field of the
 * terms version, pinned on the agreement.
 */
public enum OverpaymentTreatment {

    /** Held as a credit balance on the loan's {@code LOAN_CREDIT_BALANCE} account (the instalment loan, L6). */
    HOLD_AS_CREDIT,

    /** Applied to drawn principal not yet due, restoring the limit; only what exceeds every receivable is held (the line, A26). */
    PAY_DOWN_PRINCIPAL
}
