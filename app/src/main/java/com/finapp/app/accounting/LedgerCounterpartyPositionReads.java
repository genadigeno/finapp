package com.finapp.app.accounting;

import com.finapp.accounting.AccountingStorageException;
import com.finapp.accounting.CounterpartyPositionReads;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.OpeningCheckpoint;
import com.finapp.accounting.OpeningCheckpointStore;
import com.finapp.accounting.Side;
import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * accounting's {@link CounterpartyPositionReads} over the ledger's own tables (`P14-TSK-017`'s review, I-1; {@code INV-RAIL-04}) - in
 * {@code app}, beside {@link LedgerTrialBalanceReads}, whose statements are left alone. The join is
 * {@code LedgerJournalReads.AGGREGATES}': a {@code LEFT JOIN}, so an account whose owner reference names no counterparty row is read as an
 * aggregate with no code and refused by the report, and never dropped from a figure by an inner join.
 *
 * <p><strong>{@code SELECT} only, on the definition's snapshot</strong>, one statement the {@code ReadOnlySnapshot} view admits, under the
 * application role's existing grants; none locks a row. Never the entry's reference, reason or actor; {@code created_at} is filtered on -
 * a PROVISIONAL run's cut - and never selected. {@code owner_kind = 'COUNTERPARTY'} starts from a handful of accounts, and
 * {@code journal_line_by_account} (ledger {@code V004}) serves the walk from them.
 *
 * <p><strong>The opening, bounded</strong> (`P16-REM-003`): built with an {@link OpeningCheckpointStore}, {@link #before} starts from
 * the latest valid checkpoint's counterparty sums and folds only the lines dated after its month ({@link #AFTER}); built without one,
 * from inception.
 */
public final class LedgerCounterpartyPositionReads implements CounterpartyPositionReads {

    private static final String COLUMNS = "SELECT account.purpose, account.owner_kind, counterparty.code AS counterparty,"
            + " line.currency, line.scale, line.direction, SUM(line.amount_minor) AS total, COUNT(*) AS lines";

    private static final String LINES = " FROM ledger.journal_entry entry"
            + " JOIN ledger.journal_line line ON line.entry_id = entry.id"
            + " JOIN ledger.ledger_account account ON account.id = line.ledger_account_id"
            + " LEFT JOIN ledger.counterparty counterparty ON counterparty.id = account.owner_ref"
            + " WHERE account.owner_kind = 'COUNTERPARTY'";

    /** The opening's lines: everything dated before a day - from inception. */
    static final String BEFORE = COLUMNS + LINES + " AND entry.posting_date < ?";

    /** The movement's lines: a posting-date range, both ends inclusive. */
    static final String WITHIN = COLUMNS + LINES + " AND entry.posting_date >= ? AND entry.posting_date <= ?";

    /** After a checkpoint: only the lines dated after its month (`P16-REM-003`). */
    static final String AFTER = " AND entry.posting_date > ?";

    /** A PROVISIONAL run's cut: entries created at or before its claim. */
    static final String AT_THE_CUT = " AND entry.created_at <= ?";

    static final String GROUPS = " GROUP BY account.purpose, account.owner_kind, counterparty.code, line.currency, line.scale,"
            + " line.direction ORDER BY account.purpose, account.owner_kind, counterparty.code, line.currency, line.scale,"
            + " line.direction";

    private final Optional<OpeningCheckpointStore> checkpoints;

    /** The reads folding every opening from inception - no checkpoint. */
    public LedgerCounterpartyPositionReads() {
        this.checkpoints = Optional.empty();
    }

    /** The reads whose openings start from the latest valid checkpoint (`P16-REM-003`). */
    public LedgerCounterpartyPositionReads(OpeningCheckpointStore checkpoints) {
        this.checkpoints = Optional.of(Objects.requireNonNull(checkpoints, "checkpoints"));
    }

    @Override
    public List<JournalReads.Aggregate> before(Connection snapshot, LocalDate before, Optional<Instant> cut) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(cut, "cut");
        Optional<OpeningCheckpoint> from = checkpoints.flatMap(store -> store.latest(snapshot, before, cut))
                .filter(found -> found.precedes(before));
        try (PreparedStatement read = snapshot.prepareStatement(BEFORE + (from.isPresent() ? AFTER : "")
                + (cut.isPresent() ? AT_THE_CUT : "") + GROUPS)) {
            int parameter = 1;
            read.setDate(parameter++, Date.valueOf(before));
            if (from.isPresent()) {
                read.setDate(parameter++, Date.valueOf(from.get().through()));
            }
            if (cut.isPresent()) {
                read.setTimestamp(parameter, Timestamp.from(cut.get()));
            }
            List<JournalReads.Aggregate> after = aggregates(read);
            return from.map(checkpoint -> checkpoint.counterparties(after)).orElse(after);
        } catch (SQLException failure) {
            throw failed("reading the counterparties' opening", failure);
        }
    }

    @Override
    public List<JournalReads.Aggregate> within(Connection snapshot, JournalReads.Range range, Optional<Instant> cut) {
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(cut, "cut");
        try (PreparedStatement read = snapshot.prepareStatement(WITHIN + (cut.isPresent() ? AT_THE_CUT : "") + GROUPS)) {
            read.setDate(1, Date.valueOf(range.from()));
            read.setDate(2, Date.valueOf(range.to()));
            if (cut.isPresent()) {
                read.setTimestamp(3, Timestamp.from(cut.get()));
            }
            return aggregates(read);
        } catch (SQLException failure) {
            throw failed("reading the counterparties' movement", failure);
        }
    }

    private static List<JournalReads.Aggregate> aggregates(PreparedStatement read) throws SQLException {
        List<JournalReads.Aggregate> aggregates = new ArrayList<>();
        try (ResultSet row = read.executeQuery()) {
            while (row.next()) {
                aggregates.add(new JournalReads.Aggregate(row.getString("purpose"), row.getString("owner_kind"),
                        Optional.ofNullable(row.getString("counterparty")), row.getString("currency").stripTrailing(),
                        row.getInt("scale"), side(row), row.getBigDecimal("total").toBigIntegerExact(), row.getLong("lines")));
            }
        }
        return aggregates;
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
