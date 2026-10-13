package com.finapp.lending;

import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;

/**
 * Persistence for credit line closure requests (`P11-TSK-023`, {@code lending V018}): the {@code line_closure_request} row,
 * INSERT-only for every role, one per line. Reached only under the line's row lock (L3), for a line the caller's own
 * owner-scoped lock answered.
 */
public interface LineClosureStore {

    /** The database clock - read by the request after its lock. */
    Instant databaseNow(Connection unitOfWork);

    /**
     * Births {@code request}; the database stamps and answers its instant. {@code request.requestedAt()} is a placeholder
     * the trigger overwrites.
     */
    Instant insert(Connection unitOfWork, LineClosureRequest request, CorrelationId correlation);

    /** The line's closure request, if one was born. */
    Optional<LineClosureRequest> findByLoan(Connection unitOfWork, LoanId loan);
}
