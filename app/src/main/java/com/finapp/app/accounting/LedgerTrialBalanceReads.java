package com.finapp.app.accounting;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingStorageException;
import com.finapp.accounting.GlMappingStore;
import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.OpeningCheckpoint;
import com.finapp.accounting.OpeningCheckpointStore;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.Side;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.platform.persistence.DatabaseFailure;
import java.math.BigDecimal;
import java.math.BigInteger;
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
import java.util.UUID;

/**
 * accounting's {@link TrialBalanceReads} over the ledger's journal and accounting's period and mapping tables
 * (`P14-TSK-009`; ADR-0113 decision 2, ADR-0115; {@code INV-ACC-01}, {@code INV-ACC-06}, {@code INV-ACC-14}) - in
 * {@code app}, the one module that sees both, beside {@code LedgerJournalReads}.
 *
 * <p><strong>{@code SELECT} only, on the definition's snapshot.</strong> Every statement is one read the definition's
 * {@code ReadOnlySnapshot} view admits (no {@code ;}, none of its refused functions), under the application role's existing
 * grants; none locks a row. Never the entry's reference, reason or actor; {@code created_at} is filtered on - a
 * PROVISIONAL run's cut - and never selected.
 *
 * <p><strong>The two sides.</strong> {@link #BEFORE} and {@link #WITHIN} are the GL side - the account joined for its
 * purpose and owner kind, grouped by scale so no sum crosses one, bounded by the caller's dates. {@link #LEDGER} is the
 * ledger's own trial balance in {@code TrialBalance.sweep}'s shape - {@code journal_line} grouped by currency, scale and
 * direction, <strong>no account joined</strong> - over three windows it splits with {@code FILTER}, its bounds the
 * period row's {@code period_start} and {@code period_end} as {@link #PERIOD} read them in the same snapshot, bound as
 * values - never the code's {@code Range.of} that bounded the GL side (`P14-TSK-007`'s review, M-6), and no
 * {@code accounting} table named in a {@code ledger} statement (ADR-0006; `P14-TSK-009`'s review, M-4). The version's
 * content is the mapping store's own read ({@link GlMappingStore#content} - plain {@code SELECT}s).
 *
 * <p><strong>The opening, bounded</strong> (`P16-REM-003`; {@code THREAT_MODEL.md} section 6 item 64): built with an
 * {@link OpeningCheckpointStore}, {@link #before} and {@link #ledger} start from the latest valid checkpoint before their day -
 * a locked month's sums, still under the lock they were folded under - and fold only the lines dated after its month
 * ({@link #AFTER}), so a statement's cost is the months since the latest lock, not the journal's life. The checkpoint is read in
 * its own statements, on the same snapshot, and only its month's last day is bound to the ledger's - which still names no
 * {@code accounting} table. Built without one, both fold from inception, as the proofs' and the close's whole folds do.
 */
public final class LedgerTrialBalanceReads implements TrialBalanceReads {

    /** The period's row - its bounds, status and pin - and the pin of the period before it (one month earlier). */
    static final String PERIOD = "SELECT period.period_start, period.period_end, period.status,"
            + " period.pinned_mapping_version_id, previous.pinned_mapping_version_id AS previous_pin"
            + " FROM accounting.accounting_period period"
            + " LEFT JOIN accounting.accounting_period previous"
            + " ON previous.period_start = (period.period_start - interval '1 month')::date"
            + " WHERE period.id = ?";

    private static final String GL_COLUMNS = "SELECT account.purpose, account.owner_kind, line.currency, line.scale,"
            + " line.direction, SUM(line.amount_minor) AS total, COUNT(*) AS lines";

    private static final String GL_LINES = " FROM ledger.journal_entry entry"
            + " JOIN ledger.journal_line line ON line.entry_id = entry.id"
            + " JOIN ledger.ledger_account account ON account.id = line.ledger_account_id";

    /** The opening's lines: everything dated before a day - from inception. */
    static final String BEFORE = GL_COLUMNS + GL_LINES + " WHERE entry.posting_date < ?";

    /** The movement's lines: a posting-date range, both ends inclusive. */
    static final String WITHIN = GL_COLUMNS + GL_LINES + " WHERE entry.posting_date >= ? AND entry.posting_date <= ?";

    /** A PROVISIONAL run's cut: entries created at or before its claim. */
    static final String AT_THE_CUT = " AND entry.created_at <= ?";

    static final String GL_GROUPS = " GROUP BY account.purpose, account.owner_kind, line.currency, line.scale,"
            + " line.direction ORDER BY account.purpose, account.owner_kind, line.currency, line.scale, line.direction";

    /**
     * The ledger's own windows - before the period, within it, through its end - per currency, scale and direction, no
     * account joined, the bounds the period row's, bound as values: the statement names no {@code accounting} table
     * (ADR-0006; `P14-TSK-009`'s review, M-4).
     */
    static final String LEDGER = "SELECT line.currency, line.scale, line.direction,"
            + " SUM(line.amount_minor) FILTER (WHERE entry.posting_date < bounds.period_start) AS before_total,"
            + " COUNT(*) FILTER (WHERE entry.posting_date < bounds.period_start) AS before_lines,"
            + " SUM(line.amount_minor) FILTER (WHERE entry.posting_date >= bounds.period_start) AS within_total,"
            + " COUNT(*) FILTER (WHERE entry.posting_date >= bounds.period_start) AS within_lines,"
            + " SUM(line.amount_minor) AS through_total, COUNT(*) AS through_lines"
            + " FROM ledger.journal_line line"
            + " JOIN ledger.journal_entry entry ON entry.id = line.entry_id"
            + " CROSS JOIN (SELECT CAST(? AS date) AS period_start, CAST(? AS date) AS period_end) bounds"
            + " WHERE entry.posting_date <= bounds.period_end";

    /** After a checkpoint: only the lines dated after its month (`P16-REM-003`). */
    static final String AFTER = " AND entry.posting_date > ?";

    static final String LEDGER_GROUPS = " GROUP BY line.currency, line.scale, line.direction"
            + " ORDER BY line.currency, line.scale, line.direction";

    private final GlMappingStore mappings;

    private final Optional<OpeningCheckpointStore> checkpoints;

    /** The reads folding every opening from inception - no checkpoint. */
    public LedgerTrialBalanceReads(GlMappingStore mappings) {
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.checkpoints = Optional.empty();
    }

    /** The reads whose openings start from the latest valid checkpoint (`P16-REM-003`). */
    public LedgerTrialBalanceReads(GlMappingStore mappings, OpeningCheckpointStore checkpoints) {
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.checkpoints = Optional.of(Objects.requireNonNull(checkpoints, "checkpoints"));
    }

    @Override
    public Optional<Period> period(Connection snapshot, AccountingPeriodId id) {
        Objects.requireNonNull(id, "id");
        try (PreparedStatement read = snapshot.prepareStatement(PERIOD)) {
            read.setObject(1, id.value());
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Period(id, row.getDate("period_start").toLocalDate(),
                        row.getDate("period_end").toLocalDate(), PeriodStatus.valueOf(row.getString("status")),
                        version(row, "pinned_mapping_version_id"), version(row, "previous_pin")));
            }
        } catch (SQLException failure) {
            throw failed("reading the trial balance's period", failure);
        }
    }

    @Override
    public GlMappingVersion version(Connection snapshot, GlMappingVersionId id) {
        return mappings.content(snapshot, id);
    }

    @Override
    public List<JournalReads.Aggregate> before(Connection snapshot, LocalDate before, Optional<Instant> cut) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(cut, "cut");
        Optional<OpeningCheckpoint> from = checkpoint(snapshot, before, cut);
        try (PreparedStatement read = snapshot.prepareStatement(BEFORE + (from.isPresent() ? AFTER : "")
                + (cut.isPresent() ? AT_THE_CUT : "") + GL_GROUPS)) {
            int parameter = 1;
            read.setDate(parameter++, Date.valueOf(before));
            if (from.isPresent()) {
                read.setDate(parameter++, Date.valueOf(from.get().through()));
            }
            if (cut.isPresent()) {
                read.setTimestamp(parameter, Timestamp.from(cut.get()));
            }
            List<JournalReads.Aggregate> after = aggregates(read);
            return from.map(checkpoint -> checkpoint.gl(after)).orElse(after);
        } catch (SQLException failure) {
            throw failed("reading the trial balance's opening", failure);
        }
    }

    @Override
    public List<JournalReads.Aggregate> within(Connection snapshot, JournalReads.Range range, Optional<Instant> cut) {
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(cut, "cut");
        try (PreparedStatement read = snapshot.prepareStatement(WITHIN + (cut.isPresent() ? AT_THE_CUT : "")
                + GL_GROUPS)) {
            read.setDate(1, Date.valueOf(range.from()));
            read.setDate(2, Date.valueOf(range.to()));
            if (cut.isPresent()) {
                read.setTimestamp(3, Timestamp.from(cut.get()));
            }
            return aggregates(read);
        } catch (SQLException failure) {
            throw failed("reading the trial balance's movement", failure);
        }
    }

    @Override
    public List<LedgerWindows> ledger(Connection snapshot, Period period, Optional<Instant> cut) {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(cut, "cut");
        Optional<OpeningCheckpoint> from = checkpoint(snapshot, period.start(), cut);
        try (PreparedStatement read = snapshot.prepareStatement(LEDGER + (from.isPresent() ? AFTER : "")
                + (cut.isPresent() ? AT_THE_CUT : "") + LEDGER_GROUPS)) {
            int parameter = 1;
            read.setDate(parameter++, Date.valueOf(period.start()));
            read.setDate(parameter++, Date.valueOf(period.end()));
            if (from.isPresent()) {
                read.setDate(parameter++, Date.valueOf(from.get().through()));
            }
            if (cut.isPresent()) {
                read.setTimestamp(parameter, Timestamp.from(cut.get()));
            }
            List<LedgerWindows> windows = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    windows.add(new LedgerWindows(currency(row), row.getInt("scale"), side(row),
                            minor(row, "before_total"), row.getLong("before_lines"),
                            minor(row, "within_total"), row.getLong("within_lines"),
                            minor(row, "through_total"), row.getLong("through_lines")));
                }
            }
            return from.map(checkpoint -> checkpoint.ledger(windows)).orElse(windows);
        } catch (SQLException failure) {
            throw failed("reading the ledger's trial balance windows", failure);
        }
    }

    /** The latest valid checkpoint a read before {@code before} may start from, when the reads take one. */
    private Optional<OpeningCheckpoint> checkpoint(Connection snapshot, LocalDate before, Optional<Instant> cut) {
        return checkpoints.flatMap(store -> store.latest(snapshot, before, cut)).filter(found -> found.precedes(before));
    }

    // ------------------------------------------------------------------ plumbing

    private static List<JournalReads.Aggregate> aggregates(PreparedStatement read) throws SQLException {
        List<JournalReads.Aggregate> aggregates = new ArrayList<>();
        try (ResultSet row = read.executeQuery()) {
            while (row.next()) {
                aggregates.add(new JournalReads.Aggregate(row.getString("purpose"), row.getString("owner_kind"),
                        Optional.empty(), currency(row), row.getInt("scale"), side(row), minor(row, "total"),
                        row.getLong("lines")));
            }
        }
        return aggregates;
    }

    private static Optional<GlMappingVersionId> version(ResultSet row, String column) throws SQLException {
        UUID id = row.getObject(column, UUID.class);
        return id == null ? Optional.empty() : Optional.of(GlMappingVersionId.of(id));
    }

    /** A {@code SUM(bigint)} - {@code numeric}, exact; {@code NULL} (a window no line fell in) is zero. */
    private static BigInteger minor(ResultSet row, String column) throws SQLException {
        BigDecimal sum = row.getBigDecimal(column);
        return sum == null ? BigInteger.ZERO : sum.toBigIntegerExact();
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
