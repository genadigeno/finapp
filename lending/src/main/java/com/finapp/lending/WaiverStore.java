package com.finapp.lending;

import com.finapp.creditmath.DueItemId;
import com.finapp.ledger.JournalEntryId;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for waivers (`P11-TSK-025`, {@code lending V021}): the born-once proposal ({@code waiver}) and its born-once
 * decision ({@code waiver_decision}, {@code UNIQUE (waiver_id)} - decided once). Both INSERT-only: a waiver carries money,
 * and no lending table carrying money takes an UPDATE. Every write is reached under the loan's row lock (L3), which each
 * table's trigger takes again for every writer.
 */
public interface WaiverStore {

    /** The database clock - read by an approval after its locks. */
    Instant databaseNow(Connection unitOfWork);

    /** Births {@code proposal} {@code PROPOSED}; the database stamps and answers its instant. */
    Instant insert(Connection unitOfWork, NewWaiver proposal);

    /**
     * Births {@code decision} - whether this call decided the waiver: false when it was already decided
     * ({@code UNIQUE (waiver_id)}, the arbiter beneath L3).
     */
    boolean decide(Connection unitOfWork, Decision decision);

    /** The waiver {@code id} of {@code loan}, with its decision - empty when it is another loan's or none. */
    Optional<Waiver> find(Connection unitOfWork, LoanId loan, UUID id);

    /** The loan's waivers, newest first, at most {@code limit}. */
    List<Waiver> ofLoan(Connection unitOfWork, LoanId loan, int limit);

    /** Every waiver naming {@code assessment}, oldest first - its waived and refunded sums. */
    List<Waiver> ofAssessment(Connection unitOfWork, UUID assessment);

    /** Whether a waiver of {@code kind} on {@code item} of {@code loan} awaits a decision. */
    boolean pending(Connection unitOfWork, LoanId loan, WaiverKind kind, DueItemId item);

    // ------------------------------------------------------------------ rows

    /** A proposal at birth. */
    record NewWaiver(
            UUID id,
            LoanId loan,
            WaiverKind kind,
            Optional<UUID> assessment,
            DueItemId dueItem,
            Money amount,
            int agreementVersion,
            Actor proposer,
            String reason,
            CorrelationId correlation) {

        public NewWaiver {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(loan, "loan must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(assessment, "assessment must not be null");
            Objects.requireNonNull(dueItem, "dueItem must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(proposer, "proposer must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }

        @Override
        public String toString() {
            return "NewWaiver[" + id + ", " + kind + " on " + dueItem + "]";
        }
    }

    /** A decision: {@code APPROVED} with its entry, or {@code REJECTED} with none. */
    record Decision(UUID id, UUID waiver, String proposedBy, WaiverStatus outcome, Actor decider, String reason,
            Optional<JournalEntryId> journalEntry, CorrelationId correlation) {

        public Decision {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(waiver, "waiver must not be null");
            Objects.requireNonNull(proposedBy, "proposedBy must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(decider, "decider must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(journalEntry, "journalEntry must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            if (outcome == WaiverStatus.PROPOSED || (outcome == WaiverStatus.APPROVED) != journalEntry.isPresent()) {
                throw new IllegalArgumentException("a decision approves with its entry or rejects with none");
            }
        }

        @Override
        public String toString() {
            return "Decision[" + waiver + ", " + outcome + "]";
        }
    }
}
