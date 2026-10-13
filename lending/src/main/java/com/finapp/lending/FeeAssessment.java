package com.finapp.lending;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A fee assessed on a billed item (`P11-TSK-025`, {@code lending V021}; A18, {@code PHASE_11_PLAN.md} section 12.8 rule 4):
 * born once per item and kind, naming the governing agreement version whose pinned terms decided it and posting rule 4's
 * journal entry ({@code DR LOAN_FEES_DUE / CR LOAN_FEE_INCOME}). It joins its item's {@code FEES} - so it is allocated first
 * by the pinned order, and an unpaid fee keeps its item past due.
 *
 * @param dueItem the billed item it was assessed on - an instalment's billing or a line's statement
 * @param businessDate the business date of the assessing transaction in the pinned zone
 */
public record FeeAssessment(
        UUID id,
        LoanId loan,
        DueItemId dueItem,
        FeeKind kind,
        LocalDate businessDate,
        Money amount,
        LoanAgreementId agreement,
        int agreementVersion,
        JournalEntryId entry) {

    public FeeAssessment {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(loan, "loan must not be null");
        Objects.requireNonNull(dueItem, "dueItem must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(agreement, "agreement must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a fee of nothing is no fee");
        }
        if (agreementVersion < 1) {
            throw new IllegalArgumentException("agreement versions are numbered from 1");
        }
    }

    /** Identifiers only - never the amount ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "FeeAssessment[" + id + ", " + kind + " on " + dueItem + "]";
    }
}
