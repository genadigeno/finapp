package com.finapp.lending;

import com.finapp.ledger.JournalEntryId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A loan write-off as recorded (`P14-TSK-021`, {@code lending V032}): the proposal and, once decided, its decision. A write-off
 * is an act on the books - it moves an asset to expense and says nothing about what the customer owes.
 *
 * @param position what the proposal read under the loan's lock, and the revision it saw
 * @param defaultEvent the {@code DEFAULT} condition change it stands on: a loan cured and defaulted again names a later one
 * @param proposalReason a person's prose: never logged, never served to a customer
 * @param journalEntry rule 14's entry, once approved
 * @param valueDate the entry's posting and value date, once approved
 */
public record LoanWriteOff(
        UUID id,
        LoanId loan,
        LoanWriteOffPosition position,
        UUID defaultEvent,
        int agreementVersion,
        LoanWriteOffStatus status,
        String proposedBy,
        Instant proposedAt,
        String proposalReason,
        Optional<String> decidedBy,
        Optional<Instant> decidedAt,
        Optional<String> decisionReason,
        Optional<JournalEntryId> journalEntry,
        Optional<LocalDate> valueDate) {

    public LoanWriteOff {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(loan, "loan must not be null");
        Objects.requireNonNull(position, "position must not be null");
        Objects.requireNonNull(defaultEvent, "defaultEvent must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(proposedBy, "proposedBy must not be null");
        Objects.requireNonNull(proposedAt, "proposedAt must not be null");
        Objects.requireNonNull(proposalReason, "proposalReason must not be null");
        Objects.requireNonNull(decidedBy, "decidedBy must not be null");
        Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        Objects.requireNonNull(decisionReason, "decisionReason must not be null");
        Objects.requireNonNull(journalEntry, "journalEntry must not be null");
        Objects.requireNonNull(valueDate, "valueDate must not be null");
    }

    /** Identifiers and the state only - never a reason, a figure or a person ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "LoanWriteOff[" + id + ", " + loan.value() + ", " + status + "]";
    }
}
