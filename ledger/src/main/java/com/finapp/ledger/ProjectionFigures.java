package com.finapp.ledger;

/**
 * The three figures a balance projection row holds (`P14-TSK-026`, ADR-0119; V006): the settled balance and the
 * standing holds in minor units of the account's currency at the row's scale, and the applied-entry count
 * ({@code last_entry_seq}, {@code COUNT(DISTINCT entry_id)} over the account's lines). A value: two readings are the same
 * when all three agree. The currency and the scale are the {@link ProjectionRebuild}'s, one for every figure it records
 * ({@code INV-MON-05}).
 *
 * <p><strong>Never a decision's input</strong> ({@code INV-BAL-05}): a rebuild's figures are evidence of what the
 * projection said and what the journal derives, for the operator who reads the rebuild and nobody else.
 */
public record ProjectionFigures(long postedMinor, long holdsMinor, long appliedEntries) {

    public ProjectionFigures {
        if (holdsMinor < 0) {
            throw new IllegalArgumentException("standing holds are never negative (INV-BAL-04)");
        }
        if (appliedEntries < 0) {
            throw new IllegalArgumentException("an applied-entry count is never negative");
        }
    }
}
