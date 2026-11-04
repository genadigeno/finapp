package com.finapp.app.accounting;

import com.finapp.accounting.AccountingStorageException;
import com.finapp.accounting.PriorPeriodCorrections;
import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * accounting's {@link PriorPeriodCorrections} over the two schemas (`P14-TSK-013`, for `P14-TSK-016`; the {@code LedgerJournalShapes} precedent of an {@code app} read over the ledger's own tables). {@code SELECT} only, on the
 * caller's snapshot; no reason, actor or amount is read. <strong>No bean</strong>: its one consumer, `P14-TSK-016`, wires it.
 */
public final class LedgerPriorPeriodCorrections implements PriorPeriodCorrections {

    /** The month's posted adjustments, by {@code prior_period_adjustment_posted_on}; a {@code PROPOSED} or {@code REJECTED} row corrected nothing. */
    static final String ADJUSTMENTS_SQL = "SELECT a.id, c.period_start AS corrected_start, a.corrected_revision, a.adjustment_proposal_id, a.journal_entry_id, a.posting_date"
            + " FROM accounting.prior_period_adjustment a JOIN accounting.accounting_period c ON c.id = a.corrected_period_id"
            + " WHERE a.status = 'POSTED' AND a.posting_date >= ? AND a.posting_date <= ? ORDER BY a.posting_date, a.id";

    /**
     * The month's reversals of entries dated before it: the register's derivation. The namespace is the command key's, the second segment of {@code ledger.reverse:<key>} (the {@code LedgerFlooredPostings} precedent); the month is
     * computed in the statement from the original's frozen date.
     */
    static final String REVERSALS_SQL = "SELECT split_part(entry.idempotency_scope, ':', 2) AS namespace,"
            + " date_trunc('month', original.posting_date::timestamp)::date AS original_month, count(*) AS entries"
            + " FROM ledger.journal_entry entry JOIN ledger.journal_entry original ON original.id = entry.reverses_entry_id"
            + " WHERE entry.posting_date >= ? AND entry.posting_date <= ? AND original.posting_date < ?"
            + " GROUP BY 1, 2 ORDER BY 1, 2";

    /**
     * The ledger's side of the trace: the origin's entries dated in the month, each with its proposal. Compared in Java with what {@link #ADJUSTMENTS_SQL} read on the same snapshot: no {@code accounting} table named in a
     * {@code ledger} statement (ADR-0006; the rule {@code LedgerTrialBalanceReads} states).
     */
    static final String ORIGIN_ENTRIES_SQL = "SELECT proposal.id, proposal.journal_entry_id FROM ledger.adjustment_proposal proposal"
            + " JOIN ledger.journal_entry entry ON entry.id = proposal.journal_entry_id"
            + " WHERE proposal.origin = 'ACCOUNTING' AND proposal.status = 'APPROVED' AND entry.posting_date >= ? AND entry.posting_date <= ?";

    /** A proposal and the entry it posted, as either side names them. */
    record Trace(UUID proposal, UUID entry) {}

    @Override
    public Corrections ofMonth(Connection snapshot, YearMonth month) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(month, "month");
        Date first = Date.valueOf(month.atDay(1));
        Date last = Date.valueOf(month.atEndOfMonth());
        try {
            Set<Trace> traced = new HashSet<>();
            List<Adjustment> adjustments = adjustments(snapshot, first, last, traced);
            return new Corrections(adjustments, reversals(snapshot, first, last), untraced(originEntries(snapshot, first, last), traced));
        } catch (SQLException failure) {
            throw new AccountingStorageException(DatabaseFailure.describe("reading a month's prior-period corrections", failure));
        }
    }

    private static List<Adjustment> adjustments(Connection snapshot, Date first, Date last, Set<Trace> traced) throws SQLException {
        try (PreparedStatement read = snapshot.prepareStatement(ADJUSTMENTS_SQL)) {
            read.setDate(1, first);
            read.setDate(2, last);
            List<Adjustment> rows = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    traced.add(new Trace(row.getObject("adjustment_proposal_id", UUID.class), row.getObject("journal_entry_id", UUID.class)));
                    rows.add(new Adjustment(row.getObject("id", UUID.class), YearMonth.from(row.getDate("corrected_start").toLocalDate()), row.getInt("corrected_revision"),
                            row.getObject("journal_entry_id", UUID.class), row.getDate("posting_date").toLocalDate()));
                }
            }
            return rows;
        }
    }

    private static List<DomainReversals> reversals(Connection snapshot, Date first, Date last) throws SQLException {
        try (PreparedStatement read = snapshot.prepareStatement(REVERSALS_SQL)) {
            read.setDate(1, first);
            read.setDate(2, last);
            read.setDate(3, first);
            List<DomainReversals> rows = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    rows.add(new DomainReversals(row.getString("namespace"), YearMonth.from(row.getDate("original_month").toLocalDate()), row.getLong("entries")));
                }
            }
            return rows;
        }
    }

    private static List<Trace> originEntries(Connection snapshot, Date first, Date last) throws SQLException {
        try (PreparedStatement read = snapshot.prepareStatement(ORIGIN_ENTRIES_SQL)) {
            read.setDate(1, first);
            read.setDate(2, last);
            List<Trace> entries = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    entries.add(new Trace(row.getObject("id", UUID.class), row.getObject("journal_entry_id", UUID.class)));
                }
            }
            return entries;
        }
    }

    /** Entries of the origin dated in the month with no {@code POSTED} row of that month naming both them and their proposal: a writer that bypassed the door. */
    static long untraced(List<Trace> originEntries, Set<Trace> traced) {
        return originEntries.stream().filter(entry -> !traced.contains(entry)).count();
    }
}
