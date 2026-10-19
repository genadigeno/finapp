package com.finapp.bnpl;

import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the payoff (`P12-TSK-022`, {@code bnpl V014}; lending's {@code PayoffStore}): the quote born under a key
 * (the arbiter behind a lapsed claim), the quote read by id, and the execution. Every read names an obligation the caller
 * already holds under B4 - owner-scoped by {@link BnplRepaymentStore#lockOwnedBy} - never a request value unscoped; every
 * instant the database's, stamped after the lock.
 */
public interface BnplPayoffStore {

    /** The quote born under {@code requestKey} for the obligation - {@code UNIQUE (obligation_id, request_key)}. */
    Optional<BnplPayoffQuote> findQuoteByKey(Connection unitOfWork, BnplObligationId obligation, String requestKey);

    /** The obligation's quote {@code quote} - empty when it is another obligation's or none. */
    Optional<BnplPayoffQuote> findQuote(Connection unitOfWork, BnplObligationId obligation, UUID quote);

    /** Births the quote; the database stamps its instant, which is returned. */
    Instant insertQuote(Connection unitOfWork, BnplPayoffQuote quote);

    /** The obligation's execution, if it was paid off - {@code UNIQUE (obligation_id)}. */
    Optional<BnplPayoffExecution> findExecution(Connection unitOfWork, BnplObligationId obligation);

    /** Births the execution - once per quote and per obligation; the database stamps its instant, which is returned. */
    Instant insertExecution(Connection unitOfWork, BnplPayoffExecution execution);
}
