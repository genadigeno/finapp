package com.finapp.accounting;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.Optional;

/**
 * The ledger's posting cutoff as a period's lock, unlock and reopen move it (`P14-TSK-001`; {@code PHASE_14_PLAN.md}
 * sections 4.2, 13.5; ADR-0114; {@code INV-ACC-03}, {@code -08}) - commanded through the ledger's own
 * {@code PostingCutoffs} API (`P14-TSK-004`) in the period edge's own transaction, under advisory namespace {@code 14}
 * key {@code cutoff} exclusive, so no entry dated in a locked period commits after the lock. The cutoff row is the
 * ledger's, never accounting's.
 *
 * <p>Every journal entry dated on or before {@link #closedThrough} is refused by the ledger at insert
 * ({@code ledger.PeriodClosed}). The cutoff moves exactly one calendar month (C9) per call, forward or back, each move
 * recorded by the ledger with the actor and correlation of the current scope and the {@code reason} given here; the
 * ledger bounds and retries its own wait for the key. A refused move is an {@link IllegalStateException} (the ledger's
 * own type is not accounting's to see, ADR-0113): not one period, a cutoff over today, the cutoff standing elsewhere,
 * or the key still busy after the ledger's bounded retries (nothing moved; the act is retried later). A replay - the cutoff already where the move would put it - answers
 * {@code false} and writes nothing.
 *
 * <p><strong>A writer port</strong> - it moves the ledger's state - so {@code ReportingNeverWritesTest} refuses it to
 * every reporting class. Implemented by {@code app}'s {@code LedgerPostingCutoff}.
 */
public interface PostingCutoff {

    /**
     * The ledger's key stayed busy through every one of its bounded attempts - postings held it; nothing moved and the
     * act is retried later (`P14-TSK-005`: the lock's {@code accounting.CutoffBusy}, told apart from a refused move,
     * which is a defect). Still an {@link IllegalStateException}, as the port documents every refusal.
     */
    final class CutoffBusy extends IllegalStateException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public CutoffBusy(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The cutoff now - empty until the first period lock. Reads, on {@code unitOfWork}; takes no lock. */
    Optional<LocalDate> closedThrough(Connection unitOfWork);

    /**
     * A period's lock: the cutoff from {@code from} (empty when no period is locked) to the period's last day.
     *
     * @return {@code true} when it moved; {@code false} when it already stood at {@code through} (a replay)
     * @throws IllegalStateException the move is not one period forward, covers today, or the cutoff stands elsewhere
     * @throws IllegalArgumentException a date that is not a month's last day, or a blank reason (a caller defect)
     */
    boolean advance(Connection unitOfWork, Optional<LocalDate> from, LocalDate through, String reason);

    /**
     * A period's unlock or reopen: the cutoff from the period's last day back to the previous month end, or to unset
     * when that period was the first locked.
     *
     * @return {@code true} when it moved; {@code false} when it already stood at {@code to} (a replay)
     * @throws IllegalStateException the move is not exactly the one period back, or the cutoff stands elsewhere
     * @throws IllegalArgumentException a date that is not a month's last day, or a blank reason (a caller defect)
     */
    boolean moveBack(Connection unitOfWork, LocalDate from, Optional<LocalDate> to, String reason);
}
