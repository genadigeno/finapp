package com.finapp.transfers;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the transfer and its append-only lifecycle history (`P4-TSK-005`).
 *
 * <p><strong>Two writes, and the reads their surfaces earned.</strong> The execution inserts
 * the row carrying its outcome — `V002`'s grants force that design, a {@code FAILED} outcome
 * being unreachable by {@code UPDATE} — and appends the history row beside it. The reads
 * arrived with the surface that shapes their disclosure (`P4-TSK-008`'s status and list), each
 * classified on arrival in the ownership register: {@link #findOwned} and {@link #listFor}
 * carry {@code customer_id = ?} in the statement (ADR-0031 — `V002` named that column as the
 * ownership predicate's on the day it was created), and {@link #findById} serves only an
 * identifier the execution command itself just minted, never a request's. `P4-TSK-009`'s
 * reversal lock remains deferred to its own arrival.
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

    /**
     * The transfer {@code transfer}, whoever it belongs to — for a caller that already holds an
     * <em>authoritative</em> identifier: the execution command's own result inside the same
     * transaction, never a request's (`AUTHORITATIVE_ID` in the ownership register; the HTTP
     * reads go through {@link #findOwned} instead).
     */
    Optional<Transfer> findById(T unitOfWork, TransferId transfer);

    /**
     * The transfer {@code transfer} if it belongs to {@code customerId} — {@code customer_id = ?}
     * in the statement, so not-yours and unknown are one empty answer and the surface's one 404
     * (`P4-TSK-008`; the {@code CustomerAccountStore.findOwnedBy} shape).
     */
    Optional<Transfer> findOwned(T unitOfWork, TransferId transfer, UUID customerId);

    /**
     * The customer's transfers, newest first (`PHASE_4_PLAN.md` §9). Served by `V002`'s
     * {@code transfer_by_customer} index, named for this read on the day the table was created.
     */
    List<Transfer> listFor(T unitOfWork, UUID customerId);
}
