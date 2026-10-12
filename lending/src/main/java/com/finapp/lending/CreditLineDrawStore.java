package com.finapp.lending;

import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;

/**
 * Persistence for credit line draws (`P11-TSK-021`, {@code lending V013}): the {@code credit_line_draw} row, INSERT-only
 * for every role. Reached only under the line's row lock (L3), for a line the caller's own owner-scoped lock answered.
 */
public interface CreditLineDrawStore {

    /** The database clock - read by the draw after its locks (A10). */
    Instant databaseNow(Connection unitOfWork);

    /**
     * Births {@code draw}; the database stamps and answers its instant. {@code draw.drawnAt()} is a placeholder the
     * trigger overwrites.
     */
    Instant insert(Connection unitOfWork, CreditLineDraw draw, CorrelationId correlation);

    /** The line's draw under the customer's {@code drawKey}, if one was born. */
    Optional<CreditLineDraw> findByKey(Connection unitOfWork, LoanId loan, String drawKey);
}
