package com.finapp.lending;

import com.finapp.ledger.JournalEntryId;
import java.time.LocalDate;
import java.util.Objects;

/**
 * When an instalment loan's interest runs from (`P11-TSK-014`, {@code lending V008}; ADR-0097 points 2 and 7,
 * {@code LENDING_LIFECYCLES.md} section 4.11): on path W the disbursement entry's value date, born in the disbursement's
 * transaction; on path X the payout's conclusion (`P11-TSK-015`). Born once per loan ({@code UNIQUE (loan_id)}); the
 * schedule's first period starts on it, and accrual (`P11-TSK-016`) from it.
 *
 * @param journalEntry the disbursement entry whose value date this is (path W)
 */
public record AccrualStart(LoanId loan, LocalDate startDate, JournalEntryId journalEntry) {

    /** The origin a wallet disbursement records ({@code lending V008}'s {@code accrual_start_origin_is_known}). */
    public static final String DISBURSEMENT_ORIGIN = "DISBURSEMENT";

    public AccrualStart {
        Objects.requireNonNull(loan, "loan must not be null");
        Objects.requireNonNull(startDate, "startDate must not be null");
        Objects.requireNonNull(journalEntry, "journalEntry must not be null");
    }
}
