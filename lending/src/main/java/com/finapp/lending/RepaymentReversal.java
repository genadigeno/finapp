package com.finapp.lending;

import com.finapp.ledger.JournalEntryId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A repayment reversal as recorded (`P11-TSK-020`, {@code lending V016}; ADR-0099 point 4, {@code INV-LND-11}): the
 * proposal to undo one repayment exactly, its proposer and reason, and - once decided - the decider, their reason and, for
 * an approval, rule 10's journal entry. No amount: the amount is the repayment's, and nothing is recomputed - the reversal
 * names the agreement version the repayment was allocated under.
 *
 * @param agreementVersion the reversed repayment's agreement version
 * @param proposedBy the proposer's actor id
 * @param decidedBy the decider's actor id, once decided
 * @param journalEntry rule 10's entry, exactly when {@code APPROVED}
 */
public record RepaymentReversal(
        UUID id,
        LoanId loan,
        UUID repayment,
        int agreementVersion,
        RepaymentReversalStatus status,
        String proposedBy,
        Instant proposedAt,
        String proposalReason,
        Optional<String> decidedBy,
        Optional<Instant> decidedAt,
        Optional<String> decisionReason,
        Optional<JournalEntryId> journalEntry) {

    public RepaymentReversal {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(loan, "loan must not be null");
        Objects.requireNonNull(repayment, "repayment must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(proposedBy, "proposedBy must not be null");
        Objects.requireNonNull(proposedAt, "proposedAt must not be null");
        Objects.requireNonNull(proposalReason, "proposalReason must not be null");
        Objects.requireNonNull(decidedBy, "decidedBy must not be null");
        Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        Objects.requireNonNull(decisionReason, "decisionReason must not be null");
        Objects.requireNonNull(journalEntry, "journalEntry must not be null");
        if ((status == RepaymentReversalStatus.PROPOSED) != decidedBy.isEmpty()) {
            throw new IllegalArgumentException("a reversal is decided exactly when it is no longer PROPOSED");
        }
        if ((status == RepaymentReversalStatus.APPROVED) != journalEntry.isPresent()) {
            throw new IllegalArgumentException("an approved reversal, and only one, names its journal entry");
        }
    }

    /** Identifiers and the status - never a reason, which is a person's prose ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "RepaymentReversal[" + id + ", repayment " + repayment + ", " + status + "]";
    }
}
