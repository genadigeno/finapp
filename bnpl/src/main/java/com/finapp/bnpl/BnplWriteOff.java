package com.finapp.bnpl;

import com.finapp.ledger.JournalEntryId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A BNPL obligation's write-off as recorded (`P14-TSK-021`, {@code bnpl V020}): the proposal and, once decided, its decision. An act
 * on the books - it moves an asset to expense and says nothing about what the customer owes.
 *
 * @param position what the proposal read under the obligation's lock, and the revision it saw
 * @param defaultEvent the {@code DEFAULT} condition change it stands on: one cured and defaulted again names a later one
 * @param proposalReason a person's prose: never logged, never served to a customer
 * @param journalEntry F11's entry, once approved
 * @param valueDate the entry's posting and value date, once approved
 */
public record BnplWriteOff(
        UUID id,
        BnplObligationId obligation,
        BnplAgreementId agreement,
        BnplWriteOffPosition position,
        UUID defaultEvent,
        int agreementVersion,
        BnplCorrectionStatus status,
        String proposedBy,
        Instant proposedAt,
        String proposalReason,
        Optional<String> decidedBy,
        Optional<Instant> decidedAt,
        Optional<String> decisionReason,
        Optional<JournalEntryId> journalEntry,
        Optional<LocalDate> valueDate) {

    public BnplWriteOff {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(obligation, "obligation must not be null");
        Objects.requireNonNull(agreement, "agreement must not be null");
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
        return "BnplWriteOff[" + id + ", " + obligation.value() + ", " + status + "]";
    }
}
