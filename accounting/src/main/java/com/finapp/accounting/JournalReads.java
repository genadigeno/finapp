package com.finapp.accounting;

/**
 * The ledger's journal as accounting reads it (`P14-TSK-001`; {@code PHASE_14_PLAN.md} sections 4.2, 13.1; ADR-0113
 * decisions 1-2; {@code INV-ACC-06}) - the one financial truth, read through a port {@code app} implements over the
 * ledger's own read API, because {@code accounting} has no edge to {@code ledger} and must never gain one. GL aggregates
 * (journal lines summed by purpose, owner kind, currency and posting-date range) and drill-down lines with their owner
 * references - never a balance stored here, never a row of the ledger's handed over whole.
 *
 * <p><strong>Read-only.</strong> Every read through it runs in a {@code REPEATABLE READ READ ONLY} transaction
 * ({@code INV-ACC-14}, owner decision C10). <strong>Declared empty</strong>: `P14-TSK-007` (the GL read model) gives it
 * its methods and {@code app} its implementation; an interface with no implementation is free to take its shape then.
 */
public interface JournalReads {
}
