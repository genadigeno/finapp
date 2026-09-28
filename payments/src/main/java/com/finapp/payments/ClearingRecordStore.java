package com.finapp.payments;

import java.util.Optional;

/**
 * Persistence for {@link ClearingRecord} (`P7-TSK-005`): insert-only, one record per attempt
 * and one per acquirer reference platform-wide — the two unique arbiters that make ten
 * concurrent deliveries of one notice record it exactly once.
 *
 * @param <T> the unit-of-work handle (ADR-0032)
 */
public interface ClearingRecordStore<T> {

    /**
     * Inserts the record unless a unique arbiter refuses it.
     *
     * @return {@code true} when THIS call inserted the row; {@code false} when a record for
     *     the attempt, or a record under the same acquirer reference, already stands — the
     *     caller distinguishes the two with {@link #findForAttempt}
     */
    boolean insert(T unitOfWork, ClearingRecord fresh);

    /** The one clearing record of an attempt, if the rail has reported one. */
    Optional<ClearingRecord> findForAttempt(T unitOfWork, PaymentAttemptId attempt);
}
