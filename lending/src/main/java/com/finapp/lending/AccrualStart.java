package com.finapp.lending;

import com.finapp.ledger.JournalEntryId;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * When an instalment loan's interest runs from (`P11-TSK-014`, {@code lending V008} and {@code V009}; ADR-0097 points 2
 * and 7, {@code LENDING_LIFECYCLES.md} section 4.11): on path W the disbursement entry's value date, born in the
 * disbursement's transaction; on path X the business date of the payout's conclusion in the pinned zone, born with the
 * payout's terminal outcome (`P11-TSK-015`, A30). Born once per loan ({@code UNIQUE (loan_id)}); the schedule's first
 * period starts on it, and accrual (`P11-TSK-016`) from it. No row: interest has not started (path X awaiting its payout).
 *
 * @param journalEntry the disbursement entry whose value date this is - path W's, empty on path X
 */
public record AccrualStart(LoanId loan, LocalDate startDate, Origin origin, Optional<JournalEntryId> journalEntry) {

    /** What fixed the start ({@code lending V008}'s {@code accrual_start_origin_is_known}). */
    public enum Origin {
        /** Path W: the disbursement entry's value date. */
        DISBURSEMENT,
        /** Path X: the payout's conclusion (A30). */
        PAYOUT_CONCLUDED
    }

    public AccrualStart {
        Objects.requireNonNull(loan, "loan must not be null");
        Objects.requireNonNull(startDate, "startDate must not be null");
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(journalEntry, "journalEntry must not be null");
        if ((origin == Origin.DISBURSEMENT) != journalEntry.isPresent()) {
            throw new IllegalArgumentException("a disbursement's accrual start names its entry, and only it does");
        }
    }

    /** Path W's start: the disbursement entry's value date. */
    public static AccrualStart ofDisbursement(LoanId loan, LocalDate valueDate, JournalEntryId entry) {
        return new AccrualStart(loan, valueDate, Origin.DISBURSEMENT, Optional.of(entry));
    }

    /** Path X's start: the business date of the payout's conclusion. */
    public static AccrualStart ofPayout(LoanId loan, LocalDate concludedOn) {
        return new AccrualStart(loan, concludedOn, Origin.PAYOUT_CONCLUDED, Optional.empty());
    }
}
