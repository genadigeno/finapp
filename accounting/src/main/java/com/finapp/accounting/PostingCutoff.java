package com.finapp.accounting;

/**
 * The ledger's posting cutoff as a period's lock, unlock and reopen move it (`P14-TSK-001`; {@code PHASE_14_PLAN.md}
 * sections 4.2, 13.5; ADR-0114; {@code INV-ACC-03}, {@code -08}) - commanded through the ledger's own
 * {@code PostingCutoffs} API (`P14-TSK-004`) in the period edge's own transaction, under advisory namespace {@code 14}
 * exclusive, so no entry dated in a locked period commits after the lock. The cutoff row is the ledger's, never
 * accounting's.
 *
 * <p><strong>A writer port</strong> - it moves the ledger's state - so {@code ReportingNeverWritesTest} refuses it to
 * every reporting class. <strong>Declared empty</strong>: `P14-TSK-004` gives it its methods and {@code app} its
 * implementation ({@code LedgerPostingCutoff}).
 */
public interface PostingCutoff {
}
