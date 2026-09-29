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
     * <p>Arriving callers: `P3-TSK-016` reads the original a reversal references; this
     * task's own round-trip claim ({@code INV-MON-05} at {@code BIGINT} extremes) rides the
     * same rehydrate path. (`P3-TSK-018`'s statements were expected here too and arrived
     * through their own range reader instead — {@code JdbcStatementDerivation}, since a
     * statement wants an account's lines across entries, not one entry whole.)
     */
    Optional<PostedEntry> findById(T unitOfWork, JournalEntryId entryId);

    /**
     * Every committed line of every {@code REVERSAL} referencing {@code original} — the
     * domain half's read of what is already reversed (`P3-TSK-016`, {@code INV-REV-02}),
     * folded by the caller through {@code Money} (never a SQL {@code SUM} — `P3-TSK-008`).
     *
     * <p>Deliberately lock-free: this read races a concurrent reversal, and the race's
     * arbiter is `V009`'s trigger under the advisory lock on the original's identity, which
     * every writer meets.
     */
    java.util.List<JournalLine> reversalLinesOf(T unitOfWork, JournalEntryId original);

    /**
     * The one entry whose {@code idempotency_scope} is {@code scope} — the durable tie from a
     * command's posting key back to its entry (`P8-TSK-007`, ADR-0067 §8: the opening-position
     * backfill finds each completed operation's entry by
     * {@code "ledger.post:" + postingKey} and derives every fact from the posted rows, never
     * from memory). Lock-free: an entry is immutable.
     *
     * <p>The column carries no unique index (`V004`), but the idempotency kernel makes one
     * committed execution per scope-and-key the invariant — so a second row here is a defect,
     * answered loudly rather than by picking one ({@code INV-IDEM-01}).
     *
     * @throws LedgerStorageException two entries share the scope — the defect made loud
     */
    Optional<JournalEntryId> findByIdempotencyScope(T unitOfWork, String scope);

    /** One line's identity — what the completeness verifier joins on, nothing more. */
    record LineKey(JournalEntryId entry, LedgerAccountId account) {}

    /**
     * Every {@code (entry, account)} pair posted on {@code accounts} — the completeness
     * verifier's read (`P8-TSK-007`, ADR-0067 §9): each reconciled position's lines, joined
     * in the caller's one {@code REPEATABLE READ} snapshot against the pairs reconciliation
     * knows. Pairs only, deliberately — the verifier publishes counts, never an amount —
     * and lock-free: a report decides nothing, and posted lines are immutable. The recorded
     * scale path is incremental watermarks (ADR-0067 §9).
     */
    java.util.List<LineKey> lineKeysOn(
            T unitOfWork, java.util.Collection<LedgerAccountId> accounts);
}
