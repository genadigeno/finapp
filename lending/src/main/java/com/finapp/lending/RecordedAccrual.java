package com.finapp.lending;

import com.finapp.creditmath.InterestAccrual;
import com.finapp.ledger.JournalEntryId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One {@code interest_accrual} row (`P11-TSK-016`, {@code lending V010}; ADR-0094 point 4, {@code INV-LND-02}): the
 * accrual engine's figures for one account and date ({@link InterestAccrual} - the date, its period, the base, the
 * period's exact cumulative interest, what the date posts and the engine version), the agreement version they were
 * computed under, and posting rule 2's journal entry by value - empty for a zero day, which writes the row and no entry.
 * Born once per (account, date); never changed.
 *
 * <p>{@code toString} names identifiers and the date only - accrual amounts are {@code RESTRICTED-FINANCIAL}.
 */
public record RecordedAccrual(
        UUID id,
        LoanId loan,
        InterestAccrual accrual,
        LoanAgreementId agreement,
        int agreementVersion,
        Optional<JournalEntryId> entry) {

    public RecordedAccrual {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(loan, "loan must not be null");
        Objects.requireNonNull(accrual, "accrual must not be null");
        Objects.requireNonNull(agreement, "agreement must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        if (agreementVersion < 1) {
            throw new IllegalArgumentException("an agreement version is numbered from 1");
        }
        if (accrual.posted().isPositive() != entry.isPresent()) {
            throw new IllegalArgumentException("an accrual names an entry exactly when its date posts something");
        }
    }

    /** Identifiers and the date only. */
    @Override
    public String toString() {
        return "RecordedAccrual[" + loan.value() + ", " + accrual.date() + ", " + accrual.engine() + "]";
    }
}
