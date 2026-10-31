package com.finapp.app.accounting;

import com.finapp.accounting.AccountingStorageException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Function;

/**
 * The GL's one read-only snapshot per request (`P14-TSK-007`; {@code INV-ACC-14}, owner decision C10; the
 * {@code CreditReadingSnapshot} shape): a connection of its own, {@code REPEATABLE READ} and read-only, every statement of
 * the read on it, always rolled back - so the period's pin, the version, the aggregates, the ledger's totals and the
 * drill-down page see one instant (an entry wholly in or wholly out), and the database itself refuses a write.
 *
 * <p><strong>Verified, not assumed.</strong> The first statement - which also starts the snapshot - reads the session's
 * {@code transaction_isolation} and {@code transaction_read_only} back; anything but {@code repeatable read} and
 * {@code on} refuses the read before a figure is computed, so a pool, a driver or a configuration that dropped either
 * setting fails closed instead of serving figures from a writable, moving transaction.
 */
public final class GlSnapshots {

    /** The first statement: the snapshot's own settings, read back. */
    static final String VERIFY = "SELECT current_setting('transaction_isolation'),"
            + " current_setting('transaction_read_only')";

    /** Opens a connection for one read. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    /** The session was not the read-only repeatable-read snapshot it was set up as - nothing was read. */
    public static final class SnapshotRefused extends IllegalStateException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SnapshotRefused(String detail) {
            super(detail);
        }
    }

    private final Connections connections;

    public GlSnapshots(Connections connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /** {@code work} on one verified {@code REPEATABLE READ READ ONLY} snapshot, always rolled back. */
    public <R> R read(Function<Connection, R> work) {
        Objects.requireNonNull(work, "work");
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            try {
                verify(connection);
                return work.apply(connection);
            } finally {
                connection.rollback();
            }
        } catch (SQLException failure) {
            throw new AccountingStorageException("could not read a GL snapshot: SQLSTATE " + failure.getSQLState());
        }
    }

    private static void verify(Connection connection) throws SQLException {
        try (PreparedStatement read = connection.prepareStatement(VERIFY); ResultSet row = read.executeQuery()) {
            if (!row.next()) {
                throw new SnapshotRefused("the GL snapshot's settings could not be read back");
            }
            String isolation = row.getString(1);
            String readOnly = row.getString(2);
            if (!"repeatable read".equals(isolation) || !"on".equals(readOnly)) {
                throw new SnapshotRefused("the GL reads only in a REPEATABLE READ READ ONLY snapshot; the session was "
                        + isolation + ", read only " + readOnly);
            }
        }
    }
}
