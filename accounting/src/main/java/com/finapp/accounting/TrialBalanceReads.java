package com.finapp.accounting;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the period trial balance reads (`P14-TSK-009`; {@code PHASE_14_PLAN.md} section 13.6; ADR-0113 decision 2,
 * ADR-0115; {@code INV-ACC-01}, {@code INV-ACC-04}, {@code INV-ACC-06}) - a port beside {@link JournalReads} that
 * {@code app} implements over the ledger's journal and accounting's own period and mapping tables, because
 * {@code accounting} has no edge to {@code ledger} and report code reaches no store.
 *
 * <p><strong>Two sides, read independently.</strong> The <em>GL side</em> - the journal lines summed per purpose, owner
 * kind, currency, scale and side, {@link #before} a date (the opening: a cumulative fold from inception) and
 * {@link #within} a range (the movement) - takes its bounds from the caller, which computes them from the run's month.
 * The <em>ledger side</em> ({@link #ledger}) is the ledger's own trial balance in {@code TrialBalance.sweep}'s shape - per
 * currency, scale and side, no account joined, so no purpose and no mapping can bend it - over three windows (before
 * the period, within it, through its end) whose bounds the statement reads <strong>itself</strong> from the period's row
 * ({@code accounting V003}'s {@code period_start} and {@code period_end}). A wrong range bound on either side lands a
 * boundary day's lines in the wrong window, and the comparison of the two sides turns false (`P14-TSK-007`'s review,
 * M-6: GL = ledger over one shared range cannot catch it).
 *
 * <p><strong>The cut.</strong> A PROVISIONAL run is cut at its claim (`P14-TSK-008`'s review, M-8): every journal read
 * of it takes {@code cut} and counts only entries created at or before it - the journal as it stood when the run was
 * asked, not when it happened to be generated. A FINAL run's range is immutable after its period's lock, so it passes
 * none.
 *
 * <p><strong>Read-only, on the caller's snapshot</strong> ({@code INV-ACC-14}): every method takes the definition's
 * {@code ReadOnlySnapshot} view of one {@code REPEATABLE READ READ ONLY} transaction and issues single {@code SELECT}s -
 * so all the reads of one generation see one instant. No reason, actor or entry reference is ever selected.
 */
public interface TrialBalanceReads {

    /** The period's row as the database holds it - its bounds, status and pinned version; empty when none has the id. */
    Optional<Period> period(Connection snapshot, AccountingPeriodId id);

    /** The content of mapping version {@code id}, as born - immutable ({@code INV-ACC-07}). */
    GlMappingVersion version(Connection snapshot, GlMappingVersionId id);

    /**
     * The opening's lines: every journal line dated before {@code before} - from inception, no lower bound - summed per
     * purpose, owner kind, currency, scale and side (the counterparty is not a trial balance dimension and is empty);
     * only entries created at or before {@code cut} when one is given.
     */
    List<JournalReads.Aggregate> before(Connection snapshot, LocalDate before, Optional<Instant> cut);

    /** The movement's lines: every journal line dated in {@code range}, summed as {@link #before} sums. */
    List<JournalReads.Aggregate> within(Connection snapshot, JournalReads.Range range, Optional<Instant> cut);

    /**
     * The ledger's own totals per currency, scale and side over three windows of {@code period} - dated before its
     * start, within it, and through its end - the bounds read by the statement from the period's row, no account joined;
     * only entries created at or before {@code cut} when one is given.
     */
    List<LedgerWindows> ledger(Connection snapshot, AccountingPeriodId period, Optional<Instant> cut);

    // ------------------------------------------------------------------ the values

    /** A period's row: its bounds (V003 holds them a calendar month), its status and the version its lock pinned. */
    record Period(AccountingPeriodId id, LocalDate start, LocalDate end, PeriodStatus status,
            Optional<GlMappingVersionId> pin) {

        public Period {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(pin, "pin");
        }
    }

    /**
     * One (currency, scale, side) of the ledger's journal over the three windows - the minor units summed exactly
     * ({@code SUM(bigint)} is {@code numeric}) and the lines counted.
     */
    record LedgerWindows(
            String currency,
            int scale,
            Side side,
            BigInteger beforeMinor,
            long beforeLines,
            BigInteger withinMinor,
            long withinLines,
            BigInteger throughMinor,
            long throughLines) {

        public LedgerWindows {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(beforeMinor, "beforeMinor");
            Objects.requireNonNull(withinMinor, "withinMinor");
            Objects.requireNonNull(throughMinor, "throughMinor");
            if (side != Side.DEBIT && side != Side.CREDIT) {
                throw new IllegalArgumentException("a journal line is a debit or a credit");
            }
            if (beforeLines < 0 || withinLines < 0 || throughLines < 0) {
                throw new IllegalArgumentException("a window's line count is never negative");
            }
        }

        public BigDecimal before() {
            return new BigDecimal(beforeMinor, scale);
        }

        public BigDecimal within() {
            return new BigDecimal(withinMinor, scale);
        }

        public BigDecimal through() {
            return new BigDecimal(throughMinor, scale);
        }
    }
}
