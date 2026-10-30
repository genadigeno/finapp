package com.finapp.accounting;

/**
 * The side a registered line takes (`P14-TSK-002`; ADR-0117 decision 1). {@link #EITHER} is the code's own truth for a
 * line whose side depends on a figure's sign - {@code ROUNDING_RESIDUAL} (credit when positive, debit when negative), a
 * bank statement's cash fold, a cover's clearing - never a way to leave a side unread.
 */
public enum Side {

    DEBIT,
    CREDIT,
    EITHER;

    /** True when a line on this side may be a debit. */
    public boolean admitsDebit() {
        return this != CREDIT;
    }

    /** True when a line on this side may be a credit. */
    public boolean admitsCredit() {
        return this != DEBIT;
    }
}
