package com.finapp.lending;

import java.util.Objects;

/**
 * Every door that would move money, or a person's correction, on a written-off loan refuses it (`P14-TSK-021`): a repayment, a
 * payoff, a prepayment, a draw, a line's closure request, a waiver, a repayment reversal, an amendment. The money stays where
 * it was - a repayment's in the customer's wallet, never taken - and what the customer owes is untouched: a write-off is an act
 * on the books, and recovery after one is a later rule. {@code 409 lending.LoanWrittenOff}, thrown under the loan's row lock
 * (L3), before anything is written; the database refuses the same acts beneath it, each trigger admitting only {@code ACTIVE}
 * or {@code CLOSING}. A rejection of a proposal left standing stays open: it posts nothing.
 */
public final class LoanWrittenOffRefused extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;

    LoanWrittenOffRefused() {
        super("the account is written off: this door takes nothing, and what is owed is unchanged");
    }

    /** Refuses when {@code loan}, read under its row lock, is written off. */
    static void refuseIfWrittenOff(Loan loan) {
        if (Objects.requireNonNull(loan, "loan").status() == LoanStatus.WRITTEN_OFF) {
            throw new LoanWrittenOffRefused();
        }
    }
}
