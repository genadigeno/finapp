package com.finapp.ledger;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@link Hold} rows (`P3-TSK-015`).
 *
 * <p><strong>Every method runs on the caller's unit of work</strong>: a hold commits with the
 * flow that commanded it or not at all, and the availability protocol — account row locked
 * {@code FOR UPDATE}, then derive, then act (ADR-0039) — is {@link HoldService}'s; this store
 * carries no lock of its own beyond each statement's.
 *
 * <p><strong>{@code moveToReleased} is a conditional {@code UPDATE} whose row count is the
 * outcome</strong>: {@code WHERE id = ? AND status = 'ACTIVE'} lets exactly one of N
 * concurrent releasers act, and the loser converges with nothing written — the arbiter behind
 * "a released hold cannot be released twice", with the schema's terminal-freeze trigger and
 * the {@code holds_minor >= 0} check as the DB-CONSTRAINT-rank layers beneath it.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface HoldStore<T> {

    /** Appends a newly placed hold. Plain and loud: placement never converges on a row. */
    void insert(T unitOfWork, Hold hold);

    /** The hold, whatever its state — the release path's first read. */
    Optional<Hold> findById(T unitOfWork, HoldId id);

    /**
     * Every {@code ACTIVE} hold on {@code account}. The availability decision's second input
     * ({@code INV-BAL-04}): summed by the caller <em>through {@code Money}</em>, never by a
     * SQL {@code SUM} — a SQL aggregate is a second implementation of monetary arithmetic
     * outside the kernel (`P3-TSK-008`'s argument, verbatim).
     */
    List<Hold> findActiveFor(T unitOfWork, LedgerAccountId account);

    /**
     * How many holds are {@code ACTIVE}, system-wide — {@code finapp.ledger.hold.active}'s
     * read (`P3-TSK-020`, the caller this method arrives with). A count, never an amount:
     * what leaves the ledger for telemetry is identifiers and counts ({@code INV-AUD-02}).
     */
    long countActive(T unitOfWork);

    /**
     * {@code ACTIVE → RELEASED}, conditionally; {@code true} means this call ended it.
     *
     * <p>{@code false} is a fact, not an error: the hold was already released, and the caller
     * converges — decrementing nothing, recording nothing.
     */
    boolean moveToReleased(T unitOfWork, HoldId id, java.time.Instant at);
}
