package com.finapp.crossborder;

import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** The cancellation request's persistence (`P9-TSK-024`, crossborder {@code V006}): born once per payment. */
public interface CancellationStore {

    /** Inserts the request once; false when the payment already carries one ({@code UNIQUE (payment_id)}). */
    boolean insert(Connection unitOfWork, UUID id, UUID paymentId, String requestedBy, String correlationId);

    /** When the payment's cancellation was requested, if it was. */
    Optional<Instant> requestedAt(Connection unitOfWork, UUID paymentId);
}
