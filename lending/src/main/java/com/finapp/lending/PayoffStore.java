package com.finapp.lending;

import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for payoffs (`P11-TSK-026`, {@code lending V022}): the {@code payoff_quote} and {@code payoff_execution} rows,
 * both INSERT-only for every role. Reached only under the loan's row lock (L3), for a loan the caller's own owner-scoped
 * lock answered - every read here is scoped by that loan.
 */
public interface PayoffStore {

    /** The database clock - read by the quote and the execution after their lock. */
    Instant databaseNow(Connection unitOfWork);

    /**
     * Births {@code quote}; the database stamps and answers its instant. {@code quote.createdAt()} is a placeholder the
     * trigger overwrites.
     */
    Instant insertQuote(Connection unitOfWork, PayoffQuote quote, CorrelationId correlation);

    /** The loan's quote under the requester's key, if one was born - the arbiter behind a lapsed claim. */
    Optional<PayoffQuote> findQuoteByKey(Connection unitOfWork, LoanId loan, String requestKey);

    /** The loan's quote {@code id}, if it is the loan's. */
    Optional<PayoffQuote> findQuote(Connection unitOfWork, LoanId loan, UUID id);

    /** Births {@code execution}; the database stamps and answers its instant. */
    Instant insertExecution(Connection unitOfWork, PayoffExecution execution, CorrelationId correlation);

    /** The loan's payoff execution, if it was paid off - a loan is paid off once. */
    Optional<PayoffExecution> findExecution(Connection unitOfWork, LoanId loan);

    /** The loan's closure, if it closed - a loan closes once. */
    Optional<LoanClosure> closureOf(Connection unitOfWork, LoanId loan);
}
