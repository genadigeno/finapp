package com.finapp.transfers;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistence for the transfer and its append-only lifecycle history (`P4-TSK-005`).
 *
 * <p><strong>Deliberately two writes and no reads.</strong> The execution inserts the row
 * carrying its outcome — `V002`'s grants force that design, a {@code FAILED} outcome being
 * unreachable by {@code UPDATE} — and appends the history row beside it. The reads arrive with
 * the surfaces that shape their disclosure (`P4-TSK-008`'s status and list, `P4-TSK-009`'s
 * reversal lock), each classified on arrival; a read with no caller is dead code carrying
 * confident javadoc (the {@code P1-TSK-013} finding). Both methods take the
 * <strong>aggregate</strong>, never a bare identifier — there is no request-supplied resource
 * identifier here for an ownership question to attach to.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface TransferStore<T> {

    /** Inserts {@code transfer} exactly as it stands — status, outcome columns and all. */
    void insert(T unitOfWork, Transfer transfer);

    /**
     * Appends one lifecycle history row: {@code transfer} arrived in its current status
     * {@code from} → {@code transfer.status()}, by {@code actorId}, at {@code occurredAt} —
     * the path a transfer took, as evidence rather than memory (ADR-0044).
     */
    void recordTransition(
            T unitOfWork, Transfer transfer, TransferStatus from, UUID actorId, Instant occurredAt);
}
