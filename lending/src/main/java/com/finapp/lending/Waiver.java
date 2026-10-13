package com.finapp.lending;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A waiver as recorded (`P11-TSK-025`, {@code lending V021}; A14, ADR-0099, {@code INV-LND-11}): the proposal to forgive an
 * amount of one fee or of one billed item's interest, its proposer and reason, and - once decided - the decider, their
 * reason and, for an approval, the entry it posted (rule 8, 8a or 9).
 *
 * @param assessment the fee assessment a {@code FEE} or {@code FEE_REFUND} names - empty for {@code INTEREST}
 * @param dueItem the billed item the fee was assessed on, or whose interest is waived
 * @param agreementVersion the governing agreement version at the proposal
 * @param proposedBy the proposer's actor id
 * @param decidedBy the decider's actor id, once decided
 * @param journalEntry the posted entry, exactly when {@code APPROVED}
 */
public record Waiver(
        UUID id,
        LoanId loan,
        WaiverKind kind,
        Optional<UUID> assessment,
        DueItemId dueItem,
        Money amount,
        int agreementVersion,
        WaiverStatus status,
        String proposedBy,
        Instant proposedAt,
        String proposalReason,
        Optional<String> decidedBy,
        Optional<Instant> decidedAt,
        Optional<String> decisionReason,
        Optional<JournalEntryId> journalEntry) {

    public Waiver {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(loan, "loan must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(assessment, "assessment must not be null");
        Objects.requireNonNull(dueItem, "dueItem must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(proposedBy, "proposedBy must not be null");
        Objects.requireNonNull(proposedAt, "proposedAt must not be null");
        Objects.requireNonNull(proposalReason, "proposalReason must not be null");
        Objects.requireNonNull(decidedBy, "decidedBy must not be null");
        Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        Objects.requireNonNull(decisionReason, "decisionReason must not be null");
        Objects.requireNonNull(journalEntry, "journalEntry must not be null");
        if ((kind == WaiverKind.INTEREST) != assessment.isEmpty()) {
            throw new IllegalArgumentException("a fee's waiver or refund names its assessment; an interest waiver none");
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a waiver forgives something");
        }
        if ((status == WaiverStatus.PROPOSED) != decidedBy.isEmpty()) {
            throw new IllegalArgumentException("a waiver is decided exactly when it is no longer PROPOSED");
        }
        if ((status == WaiverStatus.APPROVED) != journalEntry.isPresent()) {
            throw new IllegalArgumentException("an approved waiver, and only one, names its journal entry");
        }
    }

    /** Identifiers, the kind and the status - never the amount or a reason ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "Waiver[" + id + ", " + kind + " on " + dueItem + ", " + status + "]";
    }
}
