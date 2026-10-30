package com.finapp.ledger;

import java.io.Serial;

/**
 * The posting cutoff's refusal ({@code ledger.PeriodClosed}; `P14-TSK-004`, ADR-0114, {@code INV-ACC-03},
 * {@code INV-ACC-08}): the entry is dated on or before the cutoff - in an accounting period already locked - and the
 * ledger refuses it at insert, whoever wrote it.
 *
 * <p>The authoritative arbiter is ledger {@code V028}'s {@code BEFORE INSERT} trigger on {@code journal_entry}, under
 * advisory namespace {@code 14} key {@code cutoff} shared; this exception is {@link JdbcJournalEntryStore}'s translation
 * of its SQLSTATE {@code LDCUT}, so the posting, the reversal and the adjustment surfaces all report one named outcome.
 * PostgreSQL has aborted the caller's transaction by then: the caller rolls back, and its idempotency claim with it.
 *
 * <p>How a back-dating poster reacts is its rule's (owner decision C3, ratified 2026-10-30; built by {@code P14-TSK-006}):
 * a system poster asks the posting command for the floor ({@link PostingCommand.Dating#FLOOR_AT_FIRST_OPEN}) and is never
 * refused; a person's adjustment dated into a locked period is refused at approval by
 * {@link AdjustmentService}'s guard - this exception, raised before anything is written - and its proposal stays
 * {@code PROPOSED}. The ledger never re-dates unasked. The message names the entry or proposal and its posting date,
 * <strong>never an amount</strong> ({@code INV-AUD-02}).
 */
public final class PeriodClosedException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public PeriodClosedException(String message) {
        super(message);
    }
}
