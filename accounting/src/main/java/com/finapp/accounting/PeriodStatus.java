package com.finapp.accounting;

import java.util.EnumSet;
import java.util.Set;

/**
 * The accounting period's lifecycle (`P14-TSK-001`; {@code PHASE_14_PLAN.md} sections 6, 13.5; ADR-0114) - a calendar
 * month of {@code posting_date} (owner decision C9), closed in order. Declared here as the closed edge table the period's
 * machine (`P14-TSK-005`, `-012`) enforces by conditional {@code UPDATE ... WHERE status = :from AND revision = :rev}
 * with an append-only {@code period_event} row per edge; nothing outside the table is ever a transition.
 *
 * <pre>
 * OPEN --lock--> LOCKED --propose close--> CLOSE_PROPOSED --approve--> CLOSED
 *                LOCKED <--reject-------- CLOSE_PROPOSED
 * OPEN <--unlock (four-eyes)-- LOCKED
 * OPEN <--reopen (four-eyes, latest CLOSED only, revision + 1)-- CLOSED
 * </pre>
 *
 * <p>Only {@link #OPEN} admits postings dated in the period: from the lock's commit the ledger's cutoff refuses every
 * entry dated on or before the period's end ({@code INV-ACC-03}, {@code -08}).
 */
public enum PeriodStatus {

    OPEN,
    LOCKED,
    CLOSE_PROPOSED,
    CLOSED;

    /** The statuses this one may move to, by exactly one edge of the plan's machine. */
    public Set<PeriodStatus> next() {
        return switch (this) {
            case OPEN -> EnumSet.of(LOCKED);
            case LOCKED -> EnumSet.of(CLOSE_PROPOSED, OPEN);
            case CLOSE_PROPOSED -> EnumSet.of(CLOSED, LOCKED);
            case CLOSED -> EnumSet.of(OPEN);
        };
    }

    /** True when {@code target} is one edge away. A status never moves to itself. */
    public boolean canMoveTo(PeriodStatus target) {
        return next().contains(target);
    }

    /** True only for {@link #OPEN}: a posting dated in a period in any other status is refused by the ledger. */
    public boolean admitsPostings() {
        return this == OPEN;
    }

    /**
     * True for the edges a second person completes: the close's approval ({@code INV-ACC-09}), an unlock and a reopen
     * ({@code INV-ACC-10}) - each proposed by one holder and approved by another, never one's own ({@code INV-AUD-04}).
     * The lock (one accountant's act after the grace - `P14-TSK-005`) and a close proposal's rejection are not.
     */
    public boolean fourEyesTo(PeriodStatus target) {
        return canMoveTo(target) && (target == CLOSED || target == OPEN);
    }
}
