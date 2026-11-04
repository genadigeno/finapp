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
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * accounting's {@link PriorPeriodCorrections} over the two schemas (`P14-TSK-013`, for `P14-TSK-016`; the {@code LedgerJournalShapes} precedent of an {@code app} read over the ledger's own tables). {@code SELECT} only, on the
 * caller's snapshot; no reason, actor or amount is read. <strong>No bean</strong>: its one consumer, `P14-TSK-016`, wires it.
 */
public final class LedgerPriorPeriodCorrections implements PriorPeriodCorrections {

    /** The month's posted adjustments, by {@code prior_period_adjustment_posted_on}; a {@code PROPOSED} or {@code REJECTED} row corrected nothing. */
    static final String ADJUSTMENTS_SQL = "SELECT a.id, c.period_start AS corrected_start, a.corrected_revision, a.journal_entry_id, a.posting_date"
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

    /** Entries of the origin dated in the month with no {@code POSTED} row naming both them and their proposal: a writer that bypassed the door. */
    static final String UNTRACED_SQL = "SELECT count(*) FROM ledger.adjustment_proposal proposal"
            + " JOIN ledger.journal_entry entry ON entry.id = proposal.journal_entry_id"
            + " LEFT JOIN accounting.prior_period_adjustment adjustment ON adjustment.adjustment_proposal_id = proposal.id"
            + " AND adjustment.status = 'POSTED' AND adjustment.journal_entry_id = proposal.journal_entry_id"
            + " WHERE proposal.origin = 'ACCOUNTING' AND proposal.status = 'APPROVED' AND entry.posting_date >= ? AND entry.posting_date <= ?"
            + " AND adjustment.id IS NULL";

    @Override
    public Corrections ofMonth(Connection snapshot, YearMonth month) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(month, "month");
        Date first = Date.valueOf(month.atDay(1));
        Date last = Date.valueOf(month.atEndOfMonth());
        try {
            return new Corrections(adjustments(snapshot, first, last), reversals(snapshot, first, last), untraced(snapshot, first, last));
        } catch (SQLException failure) {
            throw new AccountingStorageException(DatabaseFailure.describe("reading a month's prior-period corrections", failure));
        }
    }

    private static List<Adjustment> adjustments(Connection snapshot, Date first, Date last) throws SQLException {
        try (PreparedStatement read = snapshot.prepareStatement(ADJUSTMENTS_SQL)) {
            read.setDate(1, first);
            read.setDate(2, last);
            List<Adjustment> rows = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
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

    private static long untraced(Connection snapshot, Date first, Date last) throws SQLException {
        try (PreparedStatement read = snapshot.prepareStatement(UNTRACED_SQL)) {
            read.setDate(1, first);
            read.setDate(2, last);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }
}
