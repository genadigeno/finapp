package com.finapp.lending;

import com.finapp.ledger.JournalEntryId;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for loan write-offs (`P14-TSK-021`, {@code lending V032}): the born-once proposal ({@code loan_write_off}) and
 * its born-once decision ({@code loan_write_off_decision}, {@code UNIQUE (write_off_id)}). Both INSERT-only, so the decision
 * row is the history; one loan edge, conditional.
 */
public interface LoanWriteOffStore {

    /** The database clock, read after the caller's lock. */
    Instant databaseNow(Connection unitOfWork);

    /** Births the proposal; the database stamps and answers the instant. */
    Instant insert(Connection unitOfWork, NewProposal proposal);

    /** Births the decision - whether this call decided it: false when it was already decided (the unique, beneath L3). */
    boolean decide(Connection unitOfWork, Decision decision);

    /** {@code loan -> WRITTEN_OFF} from {@code from}, conditional, beside the approved decision the edge function requires. */
    boolean markWrittenOff(Connection unitOfWork, LoanId loan, LoanStatus from);

    /** The write-off {@code id} of {@code loan}. */
    Optional<LoanWriteOff> find(Connection unitOfWork, LoanId loan, UUID id);

    /** Whether a write-off of {@code loan} awaits a decision - read under L3. */
    boolean pending(Connection unitOfWork, LoanId loan);

    /** The loan's approved write-off - at most one, ever. */
    Optional<LoanWriteOff> approvedOf(Connection unitOfWork, LoanId loan);

    /** The oldest write-offs awaiting a decision, never of {@code excludedParty}, oldest first, at most {@code limit}: how a stale one is found. */
    List<LoanWriteOff> oldestPending(Connection unitOfWork, UUID excludedParty, int limit);

    /** A proposal at birth; its instant is the database's. */
    record NewProposal(UUID id, LoanId loan, LoanWriteOffPosition position, UUID defaultEvent, int agreementVersion,
            Actor proposer, String reason, CorrelationId correlation) {

        public NewProposal {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(loan, "loan must not be null");
            Objects.requireNonNull(position, "position must not be null");
            Objects.requireNonNull(defaultEvent, "defaultEvent must not be null");
            Objects.requireNonNull(proposer, "proposer must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }

        /** Never the reason or a figure. */
        @Override
        public String toString() {
            return "NewProposal[" + id + ", " + loan.value() + "]";
        }
    }

    /** A decision at birth: an approval names its entry and value date, a rejection neither. */
    record Decision(UUID id, UUID writeOff, LoanId loan, String proposedBy, LoanWriteOffStatus outcome, Actor decider,
            String reason, Optional<JournalEntryId> journalEntry, Optional<LocalDate> valueDate, CorrelationId correlation) {

        public Decision {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(writeOff, "writeOff must not be null");
            Objects.requireNonNull(loan, "loan must not be null");
            Objects.requireNonNull(proposedBy, "proposedBy must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(decider, "decider must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(journalEntry, "journalEntry must not be null");
            Objects.requireNonNull(valueDate, "valueDate must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }

        /** Never the reason or a person. */
        @Override
        public String toString() {
            return "Decision[" + writeOff + ", " + outcome + "]";
        }
    }
}
