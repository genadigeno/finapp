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
 * <p>How a back-dating poster reacts (re-date into the open period, or refuse) is its own module's decision
 * ({@code P14-TSK-006}, owner decision C3) - never the ledger's, which neither re-dates nor admits. The message names
 * the entry and its posting date, <strong>never an amount</strong> ({@code INV-AUD-02}).
 */
public final class PeriodClosedException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public PeriodClosedException(String message) {
        super(message);
    }
}
