package com.finapp.lending;

import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persistence for repayments (`P11-TSK-018`, {@code lending V014}): the {@code repayment} row and its
 * {@code repayment_allocation} rows, INSERT-only for every role. The writes and the key's read are reached only under the
 * loan's row lock (L3), for a loan the caller's own owner-scoped lock answered; the customer's history read is
 * owner-scoped in its statement.
 */
public interface RepaymentStore {

    /** The database clock - read by the repayment after its locks (A10). */
    Instant databaseNow(Connection unitOfWork);

    /** The loan's repayment of {@code kind} under the requester's {@code requestKey}, if one was born. */
    Optional<Repayment> findByKey(Connection unitOfWork, LoanId loan, RepaymentKind kind, String requestKey);

    /**
     * The loan's repayment {@code id} with its allocations, whatever its kind (`P11-TSK-020` - the repayment a reversal
     * names); empty when the loan has no such repayment. Read under the loan's row lock by the reversal - the row and its
     * allocations are INSERT-only, so nothing moves under the read.
     */
    Optional<Repayment> findById(Connection unitOfWork, LoanId loan, UUID id);

    /**
     * Births {@code repayment} and its allocation rows (in their ordinals); the database stamps and answers its instant.
     * {@code repayment.receivedAt()} is a placeholder the trigger overwrites.
     */
    Instant insert(Connection unitOfWork, Repayment repayment, CorrelationId correlation);

    /** The closure {@code repayment}'s transaction brought, if it closed its loan - for a converged replay's answer. */
    Optional<LoanClosure> closedBy(Connection unitOfWork, UUID repayment);

    /**
     * The agreement and schedule versions a prepayment's transaction made (`P11-TSK-028`, {@code lending V024}) - for a
     * converged replay's answer; empty for any other repayment. Read under the loan's row lock, for a repayment of the
     * loan the caller's owner-scoped lock answered.
     */
    Optional<Rescheduled> rescheduledBy(Connection unitOfWork, LoanId loan, UUID repayment);

    /**
     * What a prepayment re-scheduled: agreement version n+1, schedule version n+1, its instalments, its level and final
     * instalments and its first due date.
     */
    record Rescheduled(int agreementVersion, int scheduleVersion, int termMonths, Money instalment, Money finalInstalment,
            LocalDate firstDueDate) {

        public Rescheduled {
            Objects.requireNonNull(instalment, "instalment must not be null");
            Objects.requireNonNull(finalInstalment, "finalInstalment must not be null");
            Objects.requireNonNull(firstDueDate, "firstDueDate must not be null");
        }

        /** The versions and the term - never an amount. */
        @Override
        public String toString() {
            return "Rescheduled[v" + agreementVersion + ", schedule v" + scheduleVersion + ", " + termMonths + " instalments]";
        }
    }

    /**
     * {@code party}'s loan {@code loan}'s repayments with their allocations, newest first in the record's order (never
     * the clock's, `P11-TSK-034`), at most {@code limit} - owner-scoped in the statement: another party's loan is the same
     * empty answer as a loan never repaid.
     */
    List<Repayment> ownedBy(Connection unitOfWork, LoanId loan, UUID party, int limit);

    // ------------------------------------------------------------------ the collection attempts (P11-TSK-019)

    /**
     * The billed items of {@code loan} a collection was attempted on, on {@code attemptDate} - read under the loan's row
     * lock (L3), so a step already taken that date is skipped ({@code UNIQUE (due_item_id, attempt_date)} beneath).
     */
    Set<DueItemId> attemptedOn(Connection unitOfWork, LoanId loan, LocalDate attemptDate);

    /** Births one collection attempt ({@code lending V015}); the database stamps its instant. */
    void insertAttempt(Connection unitOfWork, CollectionAttempt attempt, CorrelationId correlation);
}
