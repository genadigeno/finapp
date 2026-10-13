package com.finapp.lending;

import com.finapp.ledger.JournalEntryId;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persistence for repayment reversals (`P11-TSK-020`, {@code lending V016}): the money-free proposal row
 * ({@code repayment_reversal}, its decision columns updatable once under the edge trigger) and the negating rows
 * ({@code repayment_allocation_reversal}, INSERT-only). Every write is reached only under the loan's row lock (L3).
 */
public interface RepaymentReversalStore {

    /** The database clock - read by the approval after its locks (A10). */
    Instant databaseNow(Connection unitOfWork);

    /**
     * Births {@code proposal} {@code PROPOSED}; the database stamps and answers its instant - empty when another live
     * (PROPOSED or APPROVED) reversal of the repayment exists ({@code repayment_reversal_one_live}, the arbiter beneath
     * L3).
     */
    Optional<Instant> insert(Connection unitOfWork, NewProposal proposal);

    /** The reversal {@code id}, unlocked. */
    Optional<RepaymentReversal> find(Connection unitOfWork, UUID id);

    /** The reversal {@code id} {@code FOR UPDATE} (L5) - under the loan's row lock. */
    Optional<RepaymentReversal> lock(Connection unitOfWork, UUID id);

    /** The repayment's live reversal - {@code PROPOSED} or {@code APPROVED} - if one exists. */
    Optional<RepaymentReversal> live(Connection unitOfWork, UUID repayment);

    /** Decides a {@code PROPOSED} reversal, conditional on {@code PROPOSED}; whether this call moved it. */
    boolean decide(Connection unitOfWork, Decision decision);

    /** One negating row per allocation of the reversed repayment - the same amount (the database holds it to that). */
    void insertNegations(Connection unitOfWork, UUID reversal, List<RepaymentAllocation> allocations,
            java.util.function.Supplier<UUID> ids);

    /** The loan's reversals, newest first, at most {@code limit}. */
    List<RepaymentReversal> ofLoan(Connection unitOfWork, LoanId loan, int limit);

    /** Which of {@code repayments} an {@code APPROVED} reversal names - their {@code REVERSED} status, derived. */
    Set<UUID> reversedAmong(Connection unitOfWork, Collection<UUID> repayments);

    // ------------------------------------------------------------------ rows

    /** A proposal at birth. */
    record NewProposal(
            UUID id,
            LoanId loan,
            UUID repayment,
            int agreementVersion,
            Actor proposer,
            String reason,
            CorrelationId correlation) {

        public NewProposal {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(loan, "loan must not be null");
            Objects.requireNonNull(repayment, "repayment must not be null");
            Objects.requireNonNull(proposer, "proposer must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }

        @Override
        public String toString() {
            return "NewProposal[" + id + ", repayment " + repayment + "]";
        }
    }

    /** A decision: {@code APPROVED} with rule 10's entry, or {@code REJECTED} with none. */
    record Decision(UUID reversal, RepaymentReversalStatus status, Actor decider, String reason,
            Optional<JournalEntryId> journalEntry) {

        public Decision {
            Objects.requireNonNull(reversal, "reversal must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(decider, "decider must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(journalEntry, "journalEntry must not be null");
            if (status == RepaymentReversalStatus.PROPOSED
                    || (status == RepaymentReversalStatus.APPROVED) != journalEntry.isPresent()) {
                throw new IllegalArgumentException("a decision approves with its entry or rejects with none");
            }
        }

        @Override
        public String toString() {
            return "Decision[" + reversal + ", " + status + "]";
        }
    }
}
