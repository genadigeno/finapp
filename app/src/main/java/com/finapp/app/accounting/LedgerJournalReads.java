package com.finapp.app.accounting;

import com.finapp.accounting.AccountingStorageException;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.Side;
import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Array;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * accounting's {@link JournalReads} over the ledger's own tables (`P14-TSK-007`; ADR-0113 decisions 1-2;
 * {@code INV-ACC-02}, {@code INV-ACC-06}, {@code INV-ACC-14}) - in {@code app}, the one module that sees both, the
 * {@code LedgerPurposeChart} and {@code LedgerMonitoredLedger} precedent of an adapter reading the ledger's tables
 * directly: the ledger's store has no range read and a GL is no reason to grow the ledger's write-side API.
 *
 * <p><strong>{@code SELECT} only, on the caller's snapshot.</strong> Every statement is a read under the application
 * role's existing grants on {@code journal_entry}, {@code journal_line}, {@code ledger_account} and
 * {@code counterparty}; none locks a row or takes an advisory key; the caller's {@code REPEATABLE READ READ ONLY}
 * transaction refuses a write at the database. The entry's {@code reference} ({@code RESTRICTED-FINANCIAL}; free text
 * on a {@code MANUAL} adjustment), {@code reason} and {@code actor_id} ({@code RESTRICTED-PII}) are never selected; a
 * line carries its account's {@code owner_ref} - a reference, never a name.
 *
 * <p><strong>Sums.</strong> {@code SUM(amount_minor)} over {@code bigint} is {@code numeric} - exact, read back as a
 * {@code BigDecimal} and carried as a {@code BigInteger} - and {@code scale} is a grouping key, so no SQL addition
 * crosses a scale ({@code TrialBalance}'s argument, {@code INV-MON-03}). Every range is bounded by
 * {@link JournalReads.Range} and served by ledger {@code V029}'s {@code journal_entry (posting_date, id)}.
 */
public final class LedgerJournalReads implements JournalReads {

    /** The accounts' join, with the counterparty a dimension of a {@code COUNTERPARTY}-owned account. */
    private static final String FROM_LINES = " FROM ledger.journal_entry entry"
            + " JOIN ledger.journal_line line ON line.entry_id = entry.id"
            + " JOIN ledger.ledger_account account ON account.id = line.ledger_account_id"
            + " LEFT JOIN ledger.counterparty counterparty"
            + " ON account.owner_kind = 'COUNTERPARTY' AND counterparty.id = account.owner_ref";

    /** A period's lines summed per purpose, owner kind, counterparty, currency, scale and side. */
    static final String AGGREGATES = "SELECT account.purpose, account.owner_kind, counterparty.code AS counterparty,"
            + " line.currency, line.scale, line.direction, SUM(line.amount_minor) AS total, COUNT(*) AS lines"
            + FROM_LINES
            + " WHERE entry.posting_date >= ? AND entry.posting_date <= ?"
            + " GROUP BY account.purpose, account.owner_kind, counterparty.code, line.currency, line.scale,"
            + " line.direction"
            + " ORDER BY account.purpose, account.owner_kind, counterparty.code, line.currency, line.scale,"
            + " line.direction";

    /** The ledger's own totals over the same range - no account join, so no mapping and no purpose can bend them. */
    static final String TOTALS = "SELECT line.currency, line.scale, line.direction, SUM(line.amount_minor) AS total,"
            + " COUNT(*) AS lines FROM ledger.journal_entry entry"
            + " JOIN ledger.journal_line line ON line.entry_id = entry.id"
            + " WHERE entry.posting_date >= ? AND entry.posting_date <= ?"
            + " GROUP BY line.currency, line.scale, line.direction ORDER BY line.currency, line.scale, line.direction";

    private static final String LINE_COLUMNS = "SELECT entry.posting_date, entry.id AS entry_id, line.seq,"
            + " entry.value_date, entry.entry_type, line.ledger_account_id, account.purpose, account.owner_kind,"
            + " account.owner_ref, counterparty.code AS counterparty, line.direction, line.amount_minor, line.currency,"
            + " line.scale";

    /** A drill-down page's selection: the range, the currency and the figure's keys. */
    static final String LINES = LINE_COLUMNS + FROM_LINES
            + " WHERE entry.posting_date >= ? AND entry.posting_date <= ? AND line.currency = ?"
            + " AND (account.purpose || '/' || account.owner_kind) = ANY (?)";

    /**
     * After the cursor: the index-usable row predicate on {@code (posting_date, id)} first, then the line order inside
     * the cursor's own entry.
     */
    static final String AFTER = " AND (entry.posting_date, entry.id) >= (?, ?)"
            + " AND ((entry.posting_date, entry.id) > (?, ?) OR line.seq > ?)";

    static final String PAGE = " ORDER BY entry.posting_date, entry.id, line.seq LIMIT ?";

    /** One entry's header - never its reference (free text on a MANUAL adjustment), reason or actor. */
    static final String ENTRY = "SELECT id, entry_type, posting_date, value_date, reverses_entry_id"
            + " FROM ledger.journal_entry WHERE id = ?";

    /** One entry's lines, in order. */
    static final String ENTRY_LINES = LINE_COLUMNS + FROM_LINES + " WHERE line.entry_id = ? ORDER BY line.seq";

    /** The lines on the given keys dated from a day - the unmapped gauge's count. */
    static final String COUNT = "SELECT count(*) FROM ledger.journal_entry entry"
            + " JOIN ledger.journal_line line ON line.entry_id = entry.id"
            + " JOIN ledger.ledger_account account ON account.id = line.ledger_account_id"
            + " WHERE entry.posting_date >= ? AND (account.purpose || '/' || account.owner_kind) = ANY (?)";

    @Override
    public List<Aggregate> aggregates(Connection snapshot, Range range) {
        Objects.requireNonNull(range, "range");
        try (PreparedStatement read = snapshot.prepareStatement(AGGREGATES)) {
            read.setDate(1, Date.valueOf(range.from()));
            read.setDate(2, Date.valueOf(range.to()));
            List<Aggregate> aggregates = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    aggregates.add(new Aggregate(row.getString("purpose"), row.getString("owner_kind"),
                            Optional.ofNullable(row.getString("counterparty")), currency(row), row.getInt("scale"),
                            side(row), row.getBigDecimal("total").toBigIntegerExact(), row.getLong("lines")));
                }
            }
            return aggregates;
        } catch (SQLException failure) {
            throw failed("reading the journal's aggregates for a posting-date range", failure);
        }
    }

    @Override
    public List<Total> totals(Connection snapshot, Range range) {
        Objects.requireNonNull(range, "range");
        try (PreparedStatement read = snapshot.prepareStatement(TOTALS)) {
            read.setDate(1, Date.valueOf(range.from()));
            read.setDate(2, Date.valueOf(range.to()));
            List<Total> totals = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    totals.add(new Total(currency(row), row.getInt("scale"), side(row),
                            row.getBigDecimal("total").toBigIntegerExact(), row.getLong("lines")));
                }
            }
            return totals;
        } catch (SQLException failure) {
            throw failed("reading the journal's totals for a posting-date range", failure);
        }
    }

    @Override
    public List<Line> lines(Connection snapshot, LineQuery query) {
        Objects.requireNonNull(query, "query");
        String sql = LINES + (query.after().isPresent() ? AFTER : "") + PAGE;
        try (PreparedStatement read = snapshot.prepareStatement(sql)) {
            int at = 1;
            read.setDate(at++, Date.valueOf(query.range().from()));
            read.setDate(at++, Date.valueOf(query.range().to()));
            read.setString(at++, query.currency());
            read.setArray(at++, keys(snapshot, query.keys()));
            if (query.after().isPresent()) {
                LineCursor after = query.after().get();
                read.setDate(at++, Date.valueOf(after.postingDate()));
                read.setObject(at++, after.entryId());
                read.setDate(at++, Date.valueOf(after.postingDate()));
                read.setObject(at++, after.entryId());
                read.setInt(at++, after.seq());
            }
            read.setInt(at, query.limit());
            List<Line> lines = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    lines.add(line(row));
                }
            }
            return lines;
        } catch (SQLException failure) {
            throw failed("reading a GL drill-down page", failure);
        }
    }

    @Override
    public Optional<Entry> entry(Connection snapshot, UUID entryId) {
        Objects.requireNonNull(entryId, "entryId");
        try {
            UUID id;
            String type;
            LocalDate posted;
            LocalDate valued;
            Optional<UUID> reverses;
            try (PreparedStatement read = snapshot.prepareStatement(ENTRY)) {
                read.setObject(1, entryId);
                try (ResultSet row = read.executeQuery()) {
                    if (!row.next()) {
                        return Optional.empty();
                    }
                    id = row.getObject("id", UUID.class);
                    type = row.getString("entry_type");
                    posted = row.getDate("posting_date").toLocalDate();
                    valued = row.getDate("value_date").toLocalDate();
                    reverses = Optional.ofNullable(row.getObject("reverses_entry_id", UUID.class));
                }
            }
            List<Line> lines = new ArrayList<>();
            try (PreparedStatement read = snapshot.prepareStatement(ENTRY_LINES)) {
                read.setObject(1, entryId);
                try (ResultSet row = read.executeQuery()) {
                    while (row.next()) {
                        lines.add(line(row));
                    }
                }
            }
            return Optional.of(new Entry(id, type, posted, valued, reverses, lines));
        } catch (SQLException failure) {
            throw failed("reading a journal entry's GL view", failure);
        }
    }

    @Override
    public long countLines(Connection snapshot, Set<String> keys, LocalDate fromInclusive) {
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        if (keys.isEmpty()) {
            return 0;
        }
        try (PreparedStatement read = snapshot.prepareStatement(COUNT)) {
            read.setDate(1, Date.valueOf(fromInclusive));
            read.setArray(2, keys(snapshot, keys));
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        } catch (SQLException failure) {
            throw failed("counting the journal's lines on unmapped keys", failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static Line line(ResultSet row) throws SQLException {
        return new Line(
                row.getObject("entry_id", UUID.class),
                row.getInt("seq"),
                row.getDate("posting_date").toLocalDate(),
                row.getDate("value_date").toLocalDate(),
                row.getString("entry_type"),
                row.getObject("ledger_account_id", UUID.class),
                row.getString("purpose"),
                row.getString("owner_kind"),
                Optional.ofNullable(row.getObject("owner_ref", UUID.class)),
                Optional.ofNullable(row.getString("counterparty")),
                side(row),
                row.getLong("amount_minor"),
                currency(row),
                row.getInt("scale"));
    }

    private static Array keys(Connection snapshot, Set<String> keys) throws SQLException {
        return snapshot.createArrayOf("text", keys.stream().sorted().toArray());
    }

    private static String currency(ResultSet row) throws SQLException {
        return row.getString("currency").stripTrailing();
    }

    private static Side side(ResultSet row) throws SQLException {
        return switch (row.getString("direction")) {
            case "DEBIT" -> Side.DEBIT;
            case "CREDIT" -> Side.CREDIT;
            default -> throw new IllegalStateException("a journal line is a debit or a credit");
        };
    }

    private static AccountingStorageException failed(String operation, SQLException failure) {
        return new AccountingStorageException(DatabaseFailure.describe(operation, failure));
    }
}
