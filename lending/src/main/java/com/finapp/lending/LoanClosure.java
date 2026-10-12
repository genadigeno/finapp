package com.finapp.lending;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.money.Money;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A loan's closure as recorded (`P11-TSK-018`, {@code lending V014}'s {@code loan_closure}; {@code INV-LND-08},
 * {@code LENDING_LIFECYCLES.md} section 4.5): born in the transaction that brings the loan's last receivable to zero, with
 * the credit balance it refunded to the wallet - so nothing is owed either way after it. Closure is a consequence, never
 * a command.
 *
 * @param refund what the credit balance held, refunded to the wallet - zero or positive
 * @param refundEntry the refund's journal entry ({@code DR LOAN_CREDIT_BALANCE / CR CUSTOMER_WALLET}) - present exactly
 *     when the refund is positive
 * @param repayment the repayment whose transaction closed it - empty when the servicing step did, after a credit-balance
 *     application at a billing
 */
public record LoanClosure(
        UUID id, LoanId loan, ClosureReason reason, Money refund, Optional<JournalEntryId> refundEntry,
        Optional<UUID> repayment) {

    public LoanClosure {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(loan, "loan must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(refund, "refund must not be null");
        Objects.requireNonNull(refundEntry, "refundEntry must not be null");
        Objects.requireNonNull(repayment, "repayment must not be null");
        if (refund.isNegative() || refund.isPositive() != refundEntry.isPresent()) {
            throw new IllegalArgumentException("a refund's entry exists exactly when a credit balance was refunded");
        }
    }

    /** Identifiers only. */
    @Override
    public String toString() {
        return "LoanClosure[" + id + ", loan " + loan.value() + ", " + reason + "]";
    }
}
