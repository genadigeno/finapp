package com.finapp.payments;

import java.util.Optional;

/**
 * Persistence for {@link UnmatchedConfirmation} (`P7-TSK-009`, over `V017`) — append-only:
 * {@code UNIQUE (rail, scheme_reference)} is the fresh-id duplicate's arbiter (the `V015`
 * shape), the insert converges {@code ON CONFLICT DO NOTHING}, and no writer edits a row.
 */
public interface UnmatchedConfirmationStore<T> {

    /** True when THIS call inserted the row; false converges on the record that stands. */
    boolean insert(T unitOfWork, UnmatchedConfirmation confirmation);

    /** The parked row a scheme reference names on a rail — the execute arm's pre-check. */
    /**
     * Every parking with {@code id > after}, in id order, at most {@code limit} — the
     * opening-position backfill's page (`P8-TSK-007`, ADR-0067 §8; the attempt store's
     * {@code pageByStatus} classification sentence, verbatim).
     */
    java.util.List<UnmatchedConfirmation> page(T unitOfWork, java.util.UUID after, int limit);

    Optional<UnmatchedConfirmation> findByReference(
            T unitOfWork, RailId rail, ProviderReference schemeReference);

    /** How many confirmations rest parked, and the oldest wait in seconds ({@code INV-REC-05}). */
    PaymentAttemptStore.UnknownReading parkedReading(T unitOfWork);
}
