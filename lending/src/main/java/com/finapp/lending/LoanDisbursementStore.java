package com.finapp.lending;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the disbursement and the facts it fixes (`P11-TSK-014`, {@code lending V008}): the disbursement's
 * machine row ({@code loan_disbursement}, born by the acceptance), the accrual start and the schedule versions with their
 * instalments - those INSERT-only. Every time the database's; the schedule read owner-scoped in its statement.
 */
public interface LoanDisbursementStore {

    /** The database clock ({@code statement_timestamp()}), read after the caller's locks. */
    Instant databaseNow(Connection unitOfWork);

    /**
     * Claims at most {@code limit} wallet disbursements {@code PENDING} whose permit is due, in ONE statement: the permit
     * re-stamped {@code permit} ahead on the database clock, rows another claimer holds skipped ({@code FOR UPDATE SKIP
     * LOCKED}) - both paths since `P11-TSK-015`. Answers the claimed disbursements' loans.
     */
    List<LoanId> claimDue(Connection unitOfWork, int limit, Duration permit);

    /** The loan's disbursement {@code FOR UPDATE} (L5). */
    Optional<Disbursement> lock(Connection unitOfWork, LoanId loan);

    /** {@code PENDING -> POSTED} with the entry and its value date, conditional; whether this call moved it. */
    boolean post(Connection unitOfWork, LoanId loan, JournalEntryId entry, LocalDate valueDate);

    /** {@code PENDING -> FAILED} for {@code reason}, conditional; whether this call moved it. */
    boolean fail(Connection unitOfWork, LoanId loan, CancellationReason reason);

    /**
     * Births the loan's accrual start - path W: the disbursement entry's value date; path X: the business date of the
     * payout's conclusion (`P11-TSK-015`).
     */
    void insertAccrualStart(Connection unitOfWork, UUID id, AccrualStart start, CorrelationId correlation);

    /** Births a schedule version and its instalment rows. */
    void insertSchedule(Connection unitOfWork, RepaymentSchedule schedule, List<LoanInstalment> rows,
            CorrelationId correlation);

    /**
     * {@code party}'s loan's schedule version {@code version} - its latest when empty - rebuilt from its rows (the
     * schedule's construction re-asserts conservation); empty when the party has no such loan or the loan no such
     * version.
     */
    Optional<RepaymentSchedule> scheduleOwnedBy(Connection unitOfWork, LoanId loan, UUID party, Optional<Integer> version);

    /** How long the oldest {@code PENDING} disbursement has waited, on the database clock; empty when none waits. */
    Optional<Duration> oldestPendingAge(Connection unitOfWork);
}
