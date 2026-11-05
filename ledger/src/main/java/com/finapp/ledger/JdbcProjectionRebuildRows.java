package com.finapp.ledger;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * {@link ProjectionRebuildRows} over plain JDBC (`P14-TSK-026`, ADR-0119 decision 1; ADR-0033). The settled balance is
 * {@link BalanceDerivation}'s - the definition, folded through {@link Money}, never a SQL {@code SUM} (`P3-TSK-008`) - and
 * the holds fold through {@link JournalEntry#sum} at its scale, as {@code ProjectionVerification} and {@code
 * AvailableBalance} fold them. <strong>The journal is read and never written</strong>: the statements below name
 * {@code ledger.journal_line} only after {@code FROM}, and the one table written is {@code ledger.account_balance}, through
 * V006's own grant ({@code ProjectionRebuildNeverPostsTest} reads these constants).
 *
 * <p><strong>Isolation</strong>: {@link #lockRow} refuses a transaction that is not {@code READ COMMITTED}. Under it each
 * statement of {@link #derive} takes its snapshot after the row lock was granted, which is the whole protocol; under
 * {@code REPEATABLE READ} the snapshot would predate the lock (PostgreSQL would answer {@code 40001} on the locked row, but
 * the rule is stated here rather than left to that).
 */
@RequiredArgsConstructor
public final class JdbcProjectionRebuildRows implements ProjectionRebuildRows<Connection> {

    /** Whether the account exists - asked first, so an unknown account is a 404 and never an underivable history. */
    static final String EXISTS_SQL = "SELECT 1 FROM ledger.ledger_account WHERE id = ?";

    /** The row's watermark - the bracket's two ends. */
    static final String SEQ_SQL = "SELECT last_entry_seq FROM ledger.account_balance WHERE ledger_account_id = ?";

    /** The applied-entry count: V006's watermark definition. */
    static final String ENTRIES_SQL =
            "SELECT COUNT(DISTINCT entry_id) FROM ledger.journal_line WHERE ledger_account_id = ?";

    /**
     * The account, its row and its {@code ACTIVE} holds in ONE statement - one snapshot - anchored on the account so an
     * absent row still answers and an unknown account answers nothing.
     */
    static final String ROW_AND_HOLDS_SQL = "SELECT a.currency AS account_currency, b.posted_minor, b.holds_minor, b.scale,"
            + " b.currency, b.last_entry_seq, h.amount_minor AS hold_minor, h.scale AS hold_scale,"
            + " h.currency AS hold_currency"
            + " FROM ledger.ledger_account a"
            + " LEFT JOIN ledger.account_balance b ON b.ledger_account_id = a.id"
            + " LEFT JOIN ledger.hold h ON h.ledger_account_id = a.id AND h.status = 'ACTIVE'"
            + " WHERE a.id = ?";

    /** Step 1: the posting's own mode on the account row (the journal line FK's, and V007's). */
    static final String SHARE_ACCOUNT_SQL = "SELECT 1 FROM ledger.ledger_account WHERE id = ? FOR KEY SHARE";

    /** Step 2: the projection row, held to commit. */
    static final String LOCK_ROW_SQL = "SELECT posted_minor, holds_minor, scale, currency, last_entry_seq"
            + " FROM ledger.account_balance WHERE ledger_account_id = ? FOR UPDATE";

    /** Step 3's holds: the {@code ACTIVE} rows, folded in Java. */
    static final String ACTIVE_HOLDS_SQL = "SELECT amount_minor, currency, scale FROM ledger.hold"
            + " WHERE ledger_account_id = ? AND status = 'ACTIVE'";

    /** Step 4 for a missing row: never over a row a posting created first. */
    static final String INSERT_ROW_SQL = "INSERT INTO ledger.account_balance (ledger_account_id, currency, posted_minor,"
            + " holds_minor, scale, last_entry_seq, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
            + " ON CONFLICT (ledger_account_id) DO NOTHING";

    /** Step 4: V006's four writable columns, at the row's identity. */
    static final String WRITE_ROW_SQL = "UPDATE ledger.account_balance SET posted_minor = ?, holds_minor = ?,"
            + " last_entry_seq = ?, updated_at = ? WHERE ledger_account_id = ? AND scale = ? AND currency = ?";

    /** The definition of the settled balance - a seam only so a test can name it; production passes the JDBC one. */
    @NonNull private final BalanceDerivation<Connection> derivation;

    @Override
    public Observation observe(Connection unitOfWork, LedgerAccountId account) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(account, "account must not be null");
        try {
            if (!exists(unitOfWork, account)) {
                throw new ProjectionRebuildAccountNotFoundException(account);
            }
            OptionalLong seqBefore = seqOf(unitOfWork, account);
            Money settled = settled(unitOfWork, account);
            long entries = entriesOn(unitOfWork, account);
            RowAndHolds read = rowAndHolds(unitOfWork, account)
                    .orElseThrow(() -> new ProjectionRebuildAccountNotFoundException(account));
            OptionalLong seqAfter = read.row().map(row -> OptionalLong.of(row.figures().appliedEntries()))
                    .orElse(OptionalLong.empty());
            Derived derived = new Derived(settled.currency(), settled.scale(),
                    new ProjectionFigures(settled.minorUnits(), held(account, settled, read.holds()), entries));
            return new Observation(read.row(), derived, seqBefore.equals(seqAfter));
        } catch (SQLException failure) {
            throw new LedgerStorageException(DatabaseFailure.describe("observing the projection of account " + account, failure));
        }
    }

    @Override
    public boolean shareAccount(Connection unitOfWork, LedgerAccountId account) {
        try (PreparedStatement share = unitOfWork.prepareStatement(SHARE_ACCOUNT_SQL)) {
            share.setObject(1, account.value());
            try (ResultSet row = share.executeQuery()) {
                return row.next();
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(DatabaseFailure.describe("sharing the key of account " + account, failure));
        }
    }

    @Override
    public Optional<Row> lockRow(Connection unitOfWork, LedgerAccountId account) {
        try {
            if (unitOfWork.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
                throw new IllegalStateException("a projection rebuild runs under READ COMMITTED: its derivation must read a"
                        + " snapshot taken after the row lock is granted (P14-TSK-026)");
            }
            try (PreparedStatement lock = unitOfWork.prepareStatement(LOCK_ROW_SQL)) {
                lock.setObject(1, account.value());
                try (ResultSet row = lock.executeQuery()) {
                    if (!row.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new Row(CurrencyCode.of(row.getString("currency").stripTrailing()),
                            row.getShort("scale"), new ProjectionFigures(row.getLong("posted_minor"),
                                    row.getLong("holds_minor"), row.getLong("last_entry_seq"))));
                }
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(DatabaseFailure.describe("locking the projection of account " + account, failure));
        }
    }

    @Override
    public Derived derive(Connection unitOfWork, LedgerAccountId account) {
        try {
            Money settled = settled(unitOfWork, account);
            long entries = entriesOn(unitOfWork, account);
            List<Money> holds = new ArrayList<>();
            try (PreparedStatement select = unitOfWork.prepareStatement(ACTIVE_HOLDS_SQL)) {
                select.setObject(1, account.value());
                try (ResultSet row = select.executeQuery()) {
                    while (row.next()) {
                        holds.add(Money.ofPersisted(row.getLong("amount_minor"),
                                CurrencyCode.of(row.getString("currency").stripTrailing()), row.getShort("scale")));
                    }
                }
            }
            return new Derived(settled.currency(), settled.scale(),
                    new ProjectionFigures(settled.minorUnits(), held(account, settled, holds), entries));
        } catch (SQLException failure) {
            throw new LedgerStorageException(DatabaseFailure.describe("deriving the projection of account " + account, failure));
        }
    }

    @Override
    public boolean insertRow(Connection unitOfWork, LedgerAccountId account, Derived derived, Instant at) {
        try (PreparedStatement insert = unitOfWork.prepareStatement(INSERT_ROW_SQL)) {
            insert.setObject(1, account.value());
            insert.setString(2, derived.currency().code());
            insert.setLong(3, derived.figures().postedMinor());
            insert.setLong(4, derived.figures().holdsMinor());
            insert.setInt(5, derived.scale());
            insert.setLong(6, derived.figures().appliedEntries());
            insert.setTimestamp(7, Timestamp.from(at));
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new LedgerStorageException(DatabaseFailure.describe("inserting the projection of account " + account, failure));
        }
    }

    @Override
    public void writeRow(Connection unitOfWork, LedgerAccountId account, Derived derived, Instant at) {
        try (PreparedStatement update = unitOfWork.prepareStatement(WRITE_ROW_SQL)) {
            update.setLong(1, derived.figures().postedMinor());
            update.setLong(2, derived.figures().holdsMinor());
            update.setLong(3, derived.figures().appliedEntries());
            update.setTimestamp(4, Timestamp.from(at));
            update.setObject(5, account.value());
            update.setInt(6, derived.scale());
            update.setString(7, derived.currency().code());
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("the locked projection row of account " + account + " was not written at its"
                        + " scale: the lock protocol was bypassed (P14-TSK-026)");
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(DatabaseFailure.describe("rebuilding the projection of account " + account, failure));
        }
    }

    // ------------------------------------------------------------------ plumbing

    private record RowAndHolds(Optional<Row> row, List<Money> holds) {}

    private Money settled(Connection unitOfWork, LedgerAccountId account) {
        try {
            return derivation.derive(unitOfWork, account, AsOf.latest()).settled();
        } catch (UnderivableBalanceException underivable) {
            throw new ProjectionNotRebuildableException(account, "its history cannot be derived (INV-MON-03)");
        }
    }

    /** The holds at the settled balance's scale - the scale-aware zero, as {@code AvailableBalance} folds them. */
    private static long held(LedgerAccountId account, Money settled, List<Money> holds) {
        Money standing = Money.ofPersisted(0, settled.currency(), settled.scale());
        try {
            for (Money hold : holds) {
                standing = JournalEntry.sum(standing, hold);
            }
        } catch (RuntimeException unfoldable) {
            throw new ProjectionNotRebuildableException(account, "a standing hold is at a scale its history does not have"
                    + " (INV-MON-03)");
        }
        if (standing.scale() != settled.scale()) {
            throw new ProjectionNotRebuildableException(account, "a standing hold is at a scale its history does not have"
                    + " (INV-MON-03)");
        }
        return standing.minorUnits();
    }

    private static boolean exists(Connection unitOfWork, LedgerAccountId account) throws SQLException {
        try (PreparedStatement select = unitOfWork.prepareStatement(EXISTS_SQL)) {
            select.setObject(1, account.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next();
            }
        }
    }

    private static OptionalLong seqOf(Connection unitOfWork, LedgerAccountId account) throws SQLException {
        try (PreparedStatement select = unitOfWork.prepareStatement(SEQ_SQL)) {
            select.setObject(1, account.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? OptionalLong.of(row.getLong(1)) : OptionalLong.empty();
            }
        }
    }

    private static long entriesOn(Connection unitOfWork, LedgerAccountId account) throws SQLException {
        try (PreparedStatement select = unitOfWork.prepareStatement(ENTRIES_SQL)) {
            select.setObject(1, account.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static Optional<RowAndHolds> rowAndHolds(Connection unitOfWork, LedgerAccountId account) throws SQLException {
        try (PreparedStatement select = unitOfWork.prepareStatement(ROW_AND_HOLDS_SQL)) {
            select.setObject(1, account.value());
            try (ResultSet row = select.executeQuery()) {
                Optional<Row> projection = Optional.empty();
                List<Money> holds = new ArrayList<>();
                boolean any = false;
                while (row.next()) {
                    if (!any) {
                        any = true;
                        long posted = row.getLong("posted_minor");
                        if (!row.wasNull()) {
                            projection = Optional.of(new Row(CurrencyCode.of(row.getString("currency").stripTrailing()),
                                    row.getShort("scale"), new ProjectionFigures(posted, row.getLong("holds_minor"),
                                            row.getLong("last_entry_seq"))));
                        }
                    }
                    long hold = row.getLong("hold_minor");
                    if (!row.wasNull()) {
                        holds.add(Money.ofPersisted(hold, CurrencyCode.of(row.getString("hold_currency").stripTrailing()),
                                row.getShort("hold_scale")));
                    }
                }
                return any ? Optional.of(new RowAndHolds(projection, holds)) : Optional.empty();
            }
        }
    }
}
