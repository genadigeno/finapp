package com.finapp.ledger;

import java.util.Optional;

/**
 * Storage for the journal (`P3-TSK-005`).
 *
 * <p>A port (ADR-0033), and the vocabulary is deliberate: {@link #append} is the only write,
 * because the journal has no other kind — a correction is a <em>new</em> entry
 * ({@code INV-REV-01}), and the schema refuses everything else at the privilege level and by
 * trigger. The unit of work is the caller's: the command (`P3-TSK-006`) commits the entry, its
 * lines, the audit record and the outbox row together or not at all.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface JournalEntryStore<T> {

    /** An entry as stored: the accounting value plus what stands behind it. */
    record PostedEntry(JournalEntry entry, PostingAttribution attribution) {}

    /**
     * Appends the entry and its lines, in list order ({@code seq}).
     *
     * <p>Plain and loud — no converge: the entry's identifier is freshly minted by the
     * caller, so a collision is a defect, and idempotent re-execution of the <em>command</em>
     * is the idempotency kernel's job (`P3-TSK-006`), never a quiet second write here.
     * Balance is validated by the domain before this is reachable and by the deferred
     * constraint triggers at commit — two enforcement layers, blind in different directions.
     */
    void append(T unitOfWork, JournalEntry entry, PostingAttribution attribution);

    /**
     * One entry with its lines (ordered by {@code seq}) and attribution.
     *
     * <p>Arriving callers: `P3-TSK-016` reads the original a reversal references, and
     * `P3-TSK-018`'s statements read ranges; this task's own round-trip claim
     * ({@code INV-MON-05} at {@code BIGINT} extremes) rides the same rehydrate path.
     */
    Optional<PostedEntry> findById(T unitOfWork, JournalEntryId entryId);
}
