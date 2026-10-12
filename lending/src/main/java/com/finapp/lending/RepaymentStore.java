package com.finapp.lending;

import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
     * Births {@code repayment} and its allocation rows (in their ordinals); the database stamps and answers its instant.
     * {@code repayment.receivedAt()} is a placeholder the trigger overwrites.
     */
    Instant insert(Connection unitOfWork, Repayment repayment, CorrelationId correlation);

    /** The closure {@code repayment}'s transaction brought, if it closed its loan - for a converged replay's answer. */
    Optional<LoanClosure> closedBy(Connection unitOfWork, UUID repayment);

    /**
     * {@code party}'s loan {@code loan}'s repayments with their allocations, newest first, at most {@code limit} -
     * owner-scoped in the statement: another party's loan is the same empty answer as a loan never repaid.
     */
    List<Repayment> ownedBy(Connection unitOfWork, LoanId loan, UUID party, int limit);
}
