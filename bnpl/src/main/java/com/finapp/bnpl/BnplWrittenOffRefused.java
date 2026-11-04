package com.finapp.bnpl;

import java.util.Objects;

/**
 * Every door that would move money, or a person's correction, on a written-off obligation refuses it (`P14-TSK-021`): a repayment, a
 * payoff, a waiver, a repayment reversal - and a merchant's refund and a customer's dispute, whose outcome is a refund (F5, F9). F5
 * credits the receivables, which the books no longer hold; what such a refund would cancel is a recovery, a later rule. So the money
 * stays where it was: a repayment's in the customer's wallet, a refund's on the merchant's payable. What the customer owes is
 * untouched. {@code 409 bnpl.ObligationWrittenOff}, thrown under the obligation's row lock (B4), before anything is written; the
 * database refuses the same acts beneath it. A rejection of a proposal left standing stays open: it posts nothing.
 */
public final class BnplWrittenOffRefused extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;

    BnplWrittenOffRefused() {
        super("the agreement is written off: this door takes nothing, and what is owed is unchanged");
    }

    /** Refuses when {@code obligation}, read under its row lock, is written off. */
    static void refuseIfWrittenOff(BnplObligation obligation) {
        if (Objects.requireNonNull(obligation, "obligation").status() == BnplObligationStatus.WRITTEN_OFF) {
            throw new BnplWrittenOffRefused();
        }
    }
}
