package com.finapp.ledger;

/**
 * The transactional balance projection's one write seam (`P3-TSK-009`, ADR-0041): applies a
 * posted entry's effect to {@code ledger.account_balance} on the posting's own unit of work,
 * so the projection and the postings it summarises commit together or neither commits.
 *
 * <h2>There is deliberately no read method, and the absence is the point</h2>
 *
 * <p>{@code INV-BAL-05} says no financial decision is made from a projection, and ADR-0041
 * sharpens it: a hold, an overdraft check, any refusal-or-permit on funds derives from the
 * postings inside the account lock (ADR-0039). This port makes that structural rather than
 * documented — no read exists on the write seam, and each arriving reader must say what kind
 * of number it returns. The first arrived with {@code P3-TSK-010}:
 * {@link ProjectionVerification}, which returns <strong>verdicts and counts, never a
 * balance</strong> — pinned alongside this interface's single {@code void} method by
 * {@code BalanceProjectionTest}. The display query ({@code P3-TSK-018}) is still to come and
 * must do the same.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface BalanceProjection<T> {

    /**
     * Applies {@code entry}'s per-account settled deltas to the projection, incrementing each
     * touched row's {@code last_entry_seq} by exactly one — an entry is one applied fact per
     * account, however many of its lines land there.
     *
     * <p>Must run on the same unit of work as the entry's append, after it: the projection is
     * never updated for an entry that does not commit, and never misses one that does.
     *
     * @throws UnderivableBalanceException if applying would be an implicit rescale — the
     *     row's persisted scale differs from the entry's ({@code INV-MON-03}); the posting
     *     fails wholly rather than committing beside a projection that could not follow it
     */
    void apply(T unitOfWork, JournalEntry entry);
}
